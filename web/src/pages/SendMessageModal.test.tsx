import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { apiError, mockFetch, type MockRoute } from '../test/mockFetch'
import { deferred, renderApp, unverifiedMe, verifiedMe } from '../test/renderApp'
import { errorCopy } from '../api/errors'
import type { Device, Message } from '../api/types'

const devices: Device[] = [
  {
    id: 'd2',
    name: 'Old Galaxy',
    online: false,
    sims: [],
    battery: null,
    charging: null,
    last_seen_at: null,
  },
  {
    id: 'd1',
    name: 'Pixel 8',
    online: true,
    sims: [
      { slot: 0, subscription_id: 1, carrier: 'Telia' },
      { slot: 1, subscription_id: 2, carrier: 'Bite' },
    ],
    battery: 80,
    charging: false,
    last_seen_at: new Date().toISOString(),
  },
]

function msg(id: string, over: Partial<Message> = {}): Message {
  const now = new Date().toISOString()
  return {
    id,
    device_id: 'd1',
    sim_slot: null,
    to: '+37061234567',
    status: 'delivered',
    client_ref: null,
    error_code: null,
    error_message: null,
    created_at: now,
    expires_at: now,
    dispatched_at: now,
    sent_at: now,
    delivered_at: now,
    failed_at: null,
    ...over,
  }
}

const queued = msg('m9', { to: '+37060000000', status: 'queued', dispatched_at: null, sent_at: null, delivered_at: null })

function setup(...extra: MockRoute[]) {
  const m = mockFetch([
    ...extra,
    { path: '/app/me', json: verifiedMe },
    { path: '/app/devices', json: { data: devices } },
    { path: '/app/messages?limit=50', json: { data: [msg('m1')] } },
  ])
  const app = renderApp('/messages')
  return { m, ...app }
}

async function openForm(user: ReturnType<typeof userEvent.setup>) {
  await screen.findByRole('button', { name: /delivered message/i })
  await user.click(screen.getAllByRole('button', { name: /send test message/i })[0]!)
  const dialog = await screen.findByRole('dialog', { name: /send a test message/i })
  await within(dialog).findByRole('radio', { name: /pixel 8/i })
  return dialog
}

async function fill(user: ReturnType<typeof userEvent.setup>, dialog: HTMLElement, to: string, body: string) {
  await user.type(within(dialog).getByLabelText('To'), to)
  await user.type(within(dialog).getByLabelText('Message'), body)
}

describe('SendMessageModal', () => {
  it('defaults to the first online phone and posts the message with an Idempotency-Key', async () => {
    const user = userEvent.setup()
    const { m } = setup(
      { method: 'POST', path: '/app/messages', status: 202, json: queued },
      { path: '/app/messages?limit=50', json: { data: [msg('m1')] }, once: true },
      { path: '/app/messages?limit=50', json: { data: [queued, msg('m1')] } },
    )
    const dialog = await openForm(user)

    expect(within(dialog).getByRole('radio', { name: /pixel 8/i })).toBeChecked()
    expect(within(dialog).getByRole('radio', { name: /old galaxy/i })).not.toBeChecked()
    await fill(user, dialog, '+370 600 00000', 'Hi there')
    await user.click(within(dialog).getByRole('radio', { name: /15 min/i }))
    await user.click(within(dialog).getByRole('button', { name: /send message/i }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const [call] = m.callsTo('POST', '/app/messages')
    expect(call!.body).toEqual({ device_id: 'd1', to: '+37060000000', body: 'Hi there', ttl_seconds: 900 })
    expect(call!.headers['content-type']).toBe('application/json')
    expect(call!.headers['idempotency-key']).toMatch(/^[0-9a-f-]{32,36}$/)

    // Toast, and the new message is at the top of the list, flashing.
    expect(await screen.findByText('Message queued')).toBeInTheDocument()
    const rows = within(screen.getByRole('list', { name: 'Messages' })).getAllByRole('listitem')
    expect(rows[0]).toHaveAttribute('data-message-id', 'm9')
    expect(rows[0]).toHaveAttribute('data-flash', 'true')
    expect(rows).toHaveLength(2)
    // The list was refetched after the send.
    await waitFor(() => expect(m.callsTo('GET', '/app/messages?limit=50')).toHaveLength(2))
    expect(within(screen.getByRole('list', { name: 'Messages' })).getAllByRole('listitem')).toHaveLength(2)
  })

  it('sends from the chosen SIM', async () => {
    const user = userEvent.setup()
    const { m } = setup({ method: 'POST', path: '/app/messages', status: 202, json: queued })
    const dialog = await openForm(user)
    await user.click(within(dialog).getByRole('radio', { name: /sim 2 · bite/i }))
    await fill(user, dialog, '+37060000000', 'Hi')
    await user.click(within(dialog).getByRole('button', { name: /send message/i }))
    await waitFor(() => expect(m.callsTo('POST', '/app/messages')).toHaveLength(1))
    expect(m.callsTo('POST', '/app/messages')[0]!.body).toMatchObject({ sim_slot: 1, ttl_seconds: 300 })
  })

  it('shows a 422 invalid_to as a field error and stays open', async () => {
    const user = userEvent.setup()
    setup({ method: 'POST', path: '/app/messages', ...apiError(422, 'invalid_to') })
    const dialog = await openForm(user)
    await fill(user, dialog, '+37060000000', 'Hi')
    await user.click(within(dialog).getByRole('button', { name: /send message/i }))

    expect(await within(dialog).findByText(errorCopy('invalid_to'))).toBeInTheDocument()
    expect(within(dialog).getByLabelText('To')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('validates the number locally before sending', async () => {
    const user = userEvent.setup()
    const { m } = setup()
    const dialog = await openForm(user)
    await fill(user, dialog, '060000000', 'Hi')
    await user.click(within(dialog).getByRole('button', { name: /send message/i }))
    expect(await within(dialog).findByText(errorCopy('invalid_to'))).toBeInTheDocument()
    expect(m.callsTo('POST', '/app/messages')).toHaveLength(0)
  })

  it('reuses the same Idempotency-Key when retrying after a network error', async () => {
    const user = userEvent.setup()
    const { m } = setup(
      { method: 'POST', path: '/app/messages', networkError: true, once: true },
      { method: 'POST', path: '/app/messages', status: 202, json: queued },
    )
    const dialog = await openForm(user)
    await fill(user, dialog, '+37060000000', 'Hi')
    await user.click(within(dialog).getByRole('button', { name: /send message/i }))
    expect(await within(dialog).findByText(errorCopy('network_error'))).toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: /send message/i }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const calls = m.callsTo('POST', '/app/messages')
    expect(calls).toHaveLength(2)
    expect(calls[1]!.headers['idempotency-key']).toBe(calls[0]!.headers['idempotency-key'])
    expect(calls[1]!.body).toEqual(calls[0]!.body)
  })

  it('uses a new Idempotency-Key for each opening', async () => {
    const user = userEvent.setup()
    const { m } = setup({ method: 'POST', path: '/app/messages', status: 202, json: queued })
    let dialog = await openForm(user)
    await fill(user, dialog, '+37060000000', 'One')
    await user.click(within(dialog).getByRole('button', { name: /send message/i }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    await user.click(screen.getAllByRole('button', { name: /send test message/i })[0]!)
    dialog = await screen.findByRole('dialog', { name: /send a test message/i })
    expect(within(dialog).getByLabelText('Message')).toHaveValue('')
    await fill(user, dialog, '+37060000000', 'Two')
    await user.click(within(dialog).getByRole('button', { name: /send message/i }))
    await waitFor(() => expect(m.callsTo('POST', '/app/messages')).toHaveLength(2))
    const [a, b] = m.callsTo('POST', '/app/messages')
    expect(a!.headers['idempotency-key']).not.toBe(b!.headers['idempotency-key'])
  })

  it('counts segments live: GSM-7, UCS-2 and over the limit', async () => {
    const user = userEvent.setup()
    setup()
    const dialog = await openForm(user)
    const body = within(dialog).getByLabelText('Message')
    const counter = within(dialog).getByTestId('segment-counter')

    await user.click(body)
    await user.paste('a'.repeat(161))
    expect(counter).toHaveTextContent('161 chars')
    expect(counter).toHaveTextContent('2 / 3 SMS')
    expect(counter).toHaveTextContent('GSM-7')

    await user.clear(body)
    await user.paste('😀'.repeat(36))
    expect(counter).toHaveTextContent('36 chars')
    expect(counter).toHaveTextContent('2 / 3 SMS')
    expect(counter).toHaveTextContent('UCS-2')

    await user.clear(body)
    await user.paste('a'.repeat(460))
    expect(counter).toHaveTextContent('4 / 3 SMS')
    expect(within(dialog).getByRole('button', { name: /send message/i })).toBeDisabled()
  })

  it('maps 403 email_not_verified to a verify prompt', async () => {
    const user = userEvent.setup()
    setup({ method: 'POST', path: '/app/messages', ...apiError(403, 'email_not_verified') })
    const dialog = await openForm(user)
    await fill(user, dialog, '+37060000000', 'Hi')
    await user.click(within(dialog).getByRole('button', { name: /send message/i }))
    expect(await within(dialog).findByText(/verify your email first/i)).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: /resend email/i })).toBeInTheDocument()
  })

  it('shows rate limiting with mapped copy', async () => {
    const user = userEvent.setup()
    setup({ method: 'POST', path: '/app/messages', ...apiError(429, 'rate_limited') })
    const dialog = await openForm(user)
    await fill(user, dialog, '+37060000000', 'Hi')
    await user.click(within(dialog).getByRole('button', { name: /send message/i }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(errorCopy('rate_limited'))
  })

  it('is disabled with a reason for unverified users', async () => {
    mockFetch([
      { path: '/app/me', json: unverifiedMe },
      { path: '/app/devices', json: { data: devices } },
      { path: '/app/messages?limit=50', json: { data: [msg('m1')] } },
    ])
    renderApp('/messages')
    await screen.findByRole('button', { name: /delivered message/i })
    const button = screen.getByRole('button', { name: /send test message/i })
    expect(button).toHaveAttribute('aria-disabled', 'true')
    expect(button).toHaveAccessibleDescription(/verify your email to send messages/i)
  })

  it('409 idempotency_mismatch: explains, refreshes the log, and starts a new message on request', async () => {
    const user = userEvent.setup()
    const { m } = setup(
      { method: 'POST', path: '/app/messages', ...apiError(409, 'idempotency_mismatch'), once: true },
      { method: 'POST', path: '/app/messages', status: 202, json: queued },
      { path: '/app/stats', json: {} },
    )
    const dialog = await openForm(user)
    await fill(user, dialog, '+37060000000', 'Edited')
    const before = m.callsTo('GET', '/app/messages?limit=50').length
    await user.click(within(dialog).getByRole('button', { name: /send message/i }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(errorCopy('idempotency_mismatch'))
    await waitFor(() => expect(m.callsTo('GET', '/app/messages?limit=50').length).toBeGreaterThan(before))

    await user.click(within(dialog).getByRole('button', { name: /start a new message/i }))
    expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument()
    expect(within(dialog).getByLabelText('Message')).toHaveValue('')
    expect(within(dialog).getByLabelText('To')).toHaveValue('')
    await fill(user, dialog, '+37060000000', 'Again')
    await user.click(within(dialog).getByRole('button', { name: /send message/i }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const [a, b] = m.callsTo('POST', '/app/messages')
    expect(a!.headers['idempotency-key']).not.toBe(b!.headers['idempotency-key'])
  })

  it('does not prepend the queued message to a list filtered to another status', async () => {
    const user = userEvent.setup()
    const gate = deferred()
    mockFetch([
      { method: 'POST', path: '/app/messages', status: 202, json: queued },
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', json: { data: devices } },
      { path: '/app/messages?limit=50&status=delivered', json: { data: [msg('m1')] }, once: true },
      // The refetch after sending is held, so only the optimistic update could show it.
      { path: '/app/messages?limit=50&status=delivered', json: { data: [msg('m1')] }, until: gate.promise },
    ])
    renderApp('/messages?status=delivered')
    const dialog = await openForm(user)
    await fill(user, dialog, '+37060000000', 'Hi')
    await user.click(within(dialog).getByRole('button', { name: /send message/i }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /queued message/i })).not.toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /message to/i })).toHaveLength(1)
    gate.resolve()
  })

  it('highlights the sent row once, then clears the highlight', async () => {
    const user = userEvent.setup()
    setup(
      { method: 'POST', path: '/app/messages', status: 202, json: queued },
      { path: '/app/messages?limit=50', json: { data: [msg('m1')] }, once: true },
      { path: '/app/messages?limit=50', json: { data: [queued, msg('m1')] } },
    )
    const dialog = await openForm(user)
    await fill(user, dialog, '+37060000000', 'Hi')
    await user.click(within(dialog).getByRole('button', { name: /send message/i }))
    const row = (await screen.findByRole('button', { name: /queued message/i })).closest('li')!
    expect(row).toHaveAttribute('data-flash', 'true')
    await waitFor(() => expect(row).not.toHaveAttribute('data-flash'), { timeout: 3000 })
  })
})

describe('SendMessageModal: a later error', () => {
  it('drops "Start a new message" when the next attempt fails for another reason', async () => {
    const user = userEvent.setup()
    setup(
      { method: 'POST', path: '/app/messages', ...apiError(409, 'idempotency_mismatch'), once: true },
      { method: 'POST', path: '/app/messages', ...apiError(500, 'internal'), once: true },
      { path: '/app/stats', json: {} },
    )
    const dialog = await openForm(user)
    await fill(user, dialog, '+37060000000', 'Edited')
    await user.click(within(dialog).getByRole('button', { name: /send message/i }))
    await within(dialog).findByRole('button', { name: /start a new message/i })

    await user.click(within(dialog).getByRole('button', { name: /send message/i }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(errorCopy('internal'))
    expect(within(dialog).queryByRole('button', { name: /start a new message/i })).not.toBeInTheDocument()
  })
})
