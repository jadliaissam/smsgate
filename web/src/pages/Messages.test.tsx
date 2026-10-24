import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { apiError, mockFetch } from '../test/mockFetch'
import { deferred, locationOf, renderApp, unverifiedMe, verifiedMe } from '../test/renderApp'
import type { Device, Message } from '../api/types'

const MIN = 60_000
const D1 = '0190a1b2-0000-4000-8000-000000000001'
const D2 = '0190a1b2-0000-4000-8000-000000000002'
/** A UUID-shaped message id (the `?message=` param only accepts those). */
const M7 = '0190a1b2-0000-4000-8000-0000000000a7'

const devices: Device[] = [
  {
    id: D1,
    name: 'Pixel 8',
    online: true,
    sims: [],
    battery: 80,
    charging: false,
    last_seen_at: new Date().toISOString(),
  },
  {
    id: D2,
    name: 'Old Galaxy',
    online: false,
    sims: [],
    battery: null,
    charging: null,
    last_seen_at: null,
  },
]

function msg(id: string, over: Partial<Message> = {}): Message {
  const created = Date.now() - 3 * MIN
  return {
    id,
    device_id: D1,
    sim_slot: null,
    to: '+37061234567',
    status: 'delivered',
    client_ref: null,
    error_code: null,
    error_message: null,
    created_at: new Date(created).toISOString(),
    expires_at: new Date(created + 5 * MIN).toISOString(),
    dispatched_at: new Date(created + 1_000).toISOString(),
    sent_at: new Date(created + 2_000).toISOString(),
    delivered_at: new Date(created + 5_000).toISOString(),
    failed_at: null,
    ...over,
  }
}

const failed = msg('m2', {
  to: '+37069999888',
  status: 'failed',
  sent_at: null,
  delivered_at: null,
  failed_at: new Date(Date.now() - MIN).toISOString(),
  error_code: 'radio_off',
  error_message: 'The phone is in airplane mode.',
})

const base = [
  { path: '/app/me', json: verifiedMe },
  { path: '/app/devices', json: { data: devices } },
]

function rowsOf(): HTMLElement[] {
  return within(screen.getByRole('list', { name: 'Messages' })).getAllByRole('listitem')
}

describe('Messages', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('shows a skeleton, then rows with status, masked number, device and time', async () => {
    const gate = deferred()
    const m = mockFetch([...base, { path: '/app/messages?limit=50', json: { data: [msg('m1'), failed] }, until: gate.promise }])
    renderApp('/messages')

    expect(await screen.findByTestId('messages-skeleton')).toBeInTheDocument()
    gate.resolve()

    const row = await screen.findByRole('button', { name: /delivered message to \+37061234567/i })
    expect(within(row).getByText('Delivered')).toBeInTheDocument()
    // Masked except the last four digits; the full number is for screen readers.
    expect(within(row).getByText('+•••••••4567')).toHaveAttribute('aria-hidden', 'true')
    expect(within(row).getByText('+37061234567')).toHaveClass('sr-only')
    expect(within(row).getByText('Pixel 8')).toBeInTheDocument()
    expect(within(row).getByText('3 minutes ago')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /failed message to \+37069999888/i })).toBeInTheDocument()
    expect(m.callsTo('GET', '/app/messages?limit=50')).toHaveLength(1)
  })

  it('filters update the URL and the request', async () => {
    const user = userEvent.setup()
    const m = mockFetch([
      ...base,
      { path: '/app/messages?limit=50', json: { data: [msg('m1'), failed] } },
      { path: '/app/messages?limit=50&status=failed', json: { data: [failed] } },
      { path: `/app/messages?limit=50&status=failed&device_id=${D1}`, json: { data: [failed] } },
    ])
    const { router } = renderApp('/messages')
    await screen.findByRole('button', { name: /delivered message/i })

    const statusGroup = screen.getByRole('group', { name: /filter by status/i })
    await user.click(within(statusGroup).getByRole('button', { name: 'Failed' }))
    await waitFor(() => expect(locationOf(router)).toBe('/messages?status=failed'))
    expect(within(statusGroup).getByRole('button', { name: 'Failed' })).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => expect(m.callsTo('GET', '/app/messages?limit=50&status=failed')).toHaveLength(1))
    await waitFor(() => expect(screen.queryByRole('button', { name: /delivered message/i })).not.toBeInTheDocument())

    await user.selectOptions(screen.getByRole('combobox', { name: /filter by device/i }), D1)
    await waitFor(() => expect(locationOf(router)).toBe(`/messages?status=failed&device=${D1}`))
    await waitFor(() => expect(m.callsTo('GET', `/app/messages?limit=50&status=failed&device_id=${D1}`)).toHaveLength(1))

    await user.click(within(statusGroup).getByRole('button', { name: 'All' }))
    await waitFor(() => expect(locationOf(router)).toBe(`/messages?device=${D1}`))
  })

  it('reads filters from the URL and ignores unknown statuses', async () => {
    const m = mockFetch([
      ...base,
      { path: '/app/messages?limit=50&status=delivered', json: { data: [msg('m1')] } },
      { path: '/app/messages?limit=50', json: { data: [msg('m1')] } },
    ])
    renderApp('/messages?status=delivered')
    await screen.findByRole('button', { name: /delivered message/i })
    expect(screen.getByRole('button', { name: 'Delivered' })).toHaveAttribute('aria-pressed', 'true')
    expect(m.callsTo('GET', '/app/messages?limit=50&status=delivered')).toHaveLength(1)
  })

  it('load more appends the next page using next_before', async () => {
    const user = userEvent.setup()
    const m = mockFetch([
      ...base,
      { path: '/app/messages?limit=50', json: { data: [msg('m1'), msg('m2')], next_before: 'm2' } },
      { path: '/app/messages?limit=50&before=m2', json: { data: [msg('m3', { to: '+37060000003' })], next_before: null } },
    ])
    renderApp('/messages')
    await screen.findAllByRole('button', { name: /delivered message/i })
    expect(rowsOf()).toHaveLength(2)

    await user.click(screen.getByRole('button', { name: /load more/i }))
    expect(await screen.findByRole('button', { name: /\+37060000003/ })).toBeInTheDocument()
    expect(rowsOf()).toHaveLength(3)
    expect(m.callsTo('GET', '/app/messages?limit=50&before=m2')).toHaveLength(1)
    expect(screen.queryByRole('button', { name: /load more/i })).not.toBeInTheDocument()
  })

  it('opens a drawer whose timeline shows the failure and its error_code', async () => {
    const user = userEvent.setup()
    mockFetch([...base, { path: '/app/messages?limit=50', json: { data: [msg('m1'), failed] } }])
    renderApp('/messages')

    await user.click(await screen.findByRole('button', { name: /failed message to \+37069999888/i }))
    const drawer = await screen.findByRole('dialog')
    expect(within(drawer).getByRole('heading', { name: '+37069999888' })).toBeInTheDocument()
    const timeline = within(drawer).getByRole('list', { name: /timeline/i })
    const steps = within(timeline).getAllByRole('listitem')
    expect(steps.map((s) => s.getAttribute('data-state'))).toEqual(['done', 'done', 'failed'])
    expect(steps.map((s) => s.querySelector('p')?.textContent)).toEqual(['Created', 'Dispatched', 'Failed'])
    expect(within(steps[2]!).getByText('radio_off')).toHaveClass('font-mono')
    expect(within(steps[2]!).getByText(/airplane mode/i)).toBeInTheDocument()
    expect(within(drawer).getByText('Pixel 8')).toBeInTheDocument()

    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('timeline of an in-flight message marks the next step as current', async () => {
    const user = userEvent.setup()
    const queued = msg('m5', { status: 'queued', dispatched_at: null, sent_at: null, delivered_at: null })
    mockFetch([...base, { path: '/app/messages?limit=50', json: { data: [queued] } }])
    renderApp('/messages')
    await user.click(await screen.findByRole('button', { name: /queued message/i }))
    const timeline = within(await screen.findByRole('dialog')).getByRole('list', { name: /timeline/i })
    expect(within(timeline).getAllByRole('listitem').map((s) => s.getAttribute('data-state'))).toEqual([
      'done',
      'current',
      'pending',
      'pending',
    ])
  })

  it('polls every 5 s while a message is still in flight', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const queued = msg('m5', { status: 'queued', dispatched_at: null, sent_at: null, delivered_at: null })
    const m = mockFetch([
      ...base,
      { path: '/app/messages?limit=50', json: { data: [queued] }, once: true },
      { path: '/app/messages?limit=50', json: { data: [{ ...queued, status: 'delivered' }] } },
    ])
    renderApp('/messages')
    await screen.findByRole('button', { name: /queued message/i })
    await act(() => vi.advanceTimersByTimeAsync(5_000))
    expect(await screen.findByRole('button', { name: /delivered message/i })).toBeInTheDocument()
    expect(m.callsTo('GET', '/app/messages?limit=50')).toHaveLength(2)
    // Everything is terminal now: polling stops.
    await act(() => vi.advanceTimersByTimeAsync(15_000))
    expect(m.callsTo('GET', '/app/messages?limit=50')).toHaveLength(2)
  })

  it('shows an error state and Retry refetches', async () => {
    const user = userEvent.setup()
    const m = mockFetch([
      ...base,
      { path: '/app/messages?limit=50', ...apiError(500, 'internal'), once: true },
      { path: '/app/messages?limit=50', json: { data: [msg('m1')] } },
    ])
    renderApp('/messages')
    expect(await screen.findByText(/couldn’t load your messages/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /retry/i }))
    expect(await screen.findByRole('button', { name: /delivered message/i })).toBeInTheDocument()
    expect(m.callsTo('GET', '/app/messages?limit=50')).toHaveLength(2)
  })

  it('empty with devices offers a test message', async () => {
    mockFetch([...base, { path: '/app/messages?limit=50', json: { data: [] } }])
    renderApp('/messages')
    expect(await screen.findByText(/no messages yet/i)).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /send test message/i })).toHaveLength(2)
  })

  it('empty without devices links to pairing', async () => {
    mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', json: { data: [] } },
      { path: '/app/messages?limit=50', json: { data: [] } },
    ])
    renderApp('/messages')
    expect(await screen.findByText(/pair a phone to start sending/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /pair a phone/i })).toHaveAttribute('href', '/devices')
  })

  it('empty with filters offers to clear them', async () => {
    const user = userEvent.setup()
    mockFetch([
      ...base,
      { path: '/app/messages?limit=50&status=expired', json: { data: [] } },
      { path: '/app/messages?limit=50', json: { data: [msg('m1')] } },
    ])
    const { router } = renderApp('/messages?status=expired')
    await user.click(await screen.findByRole('button', { name: /clear filters/i }))
    await waitFor(() => expect(locationOf(router)).toBe('/messages'))
    expect(await screen.findByRole('button', { name: /delivered message/i })).toBeInTheDocument()
  })

  it('ignores device and message params that are not UUIDs', async () => {
    const m = mockFetch([...base, { path: '/app/messages?limit=50', json: { data: [msg('m1')] } }])
    const { router } = renderApp('/messages?device=d1&message=nope')
    await screen.findByRole('button', { name: /delivered message/i })
    expect(m.callsTo('GET', '/app/messages?limit=50')).toHaveLength(1)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    await waitFor(() => expect(locationOf(router)).toBe('/messages'))
  })

  it('the error state offers to clear active filters', async () => {
    const user = userEvent.setup()
    mockFetch([
      ...base,
      { path: '/app/messages?limit=50&status=failed', ...apiError(500, 'internal') },
      { path: '/app/messages?limit=50', json: { data: [msg('m1')] } },
    ])
    const { router } = renderApp('/messages?status=failed')
    const alert = (await screen.findByText(/couldn’t load your messages/i)).closest('[role="alert"]') as HTMLElement
    await user.click(within(alert).getByRole('button', { name: /clear filters/i }))
    await waitFor(() => expect(locationOf(router)).toBe('/messages'))
    expect(await screen.findByRole('button', { name: /delivered message/i })).toBeInTheDocument()
  })

  describe('polling bounds', () => {
    const sentAgo = (ms: number) =>
      msg('m6', { status: 'sent', delivered_at: null, sent_at: new Date(Date.now() - ms).toISOString() })

    async function pollsAfter(list: Message[]) {
      vi.useFakeTimers({ shouldAdvanceTime: true })
      const m = mockFetch([...base, { path: '/app/messages?limit=50', json: { data: list } }])
      renderApp('/messages')
      await screen.findAllByRole('button', { name: /message to/i })
      await act(() => vi.advanceTimersByTimeAsync(11_000))
      return m.callsTo('GET', '/app/messages?limit=50').length - 1
    }

    it('an old sent message (no delivery report) does not poll', async () => {
      expect(await pollsAfter([sentAgo(20 * MIN), msg('m1')])).toBe(0)
    })

    it('a recently sent message polls', async () => {
      expect(await pollsAfter([sentAgo(2 * MIN)])).toBe(2)
    })

    it('a dispatched message past the device-lost window does not poll', async () => {
      const created = Date.now() - 60 * MIN
      const lost = msg('m7', {
        status: 'dispatched',
        created_at: new Date(created).toISOString(),
        expires_at: new Date(created + 5 * MIN).toISOString(),
        sent_at: null,
        delivered_at: null,
      })
      expect(await pollsAfter([lost])).toBe(0)
    })

    it('a dispatched message within the window polls', async () => {
      const lost = msg('m7', { status: 'dispatched', sent_at: null, delivered_at: null })
      expect(await pollsAfter([lost])).toBe(2)
    })

    it('refetches every loaded page once per interval', async () => {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      vi.useFakeTimers({ shouldAdvanceTime: true })
      const queued = msg('m5', { status: 'queued', dispatched_at: null, sent_at: null, delivered_at: null })
      const m = mockFetch([
        ...base,
        { path: '/app/messages?limit=50', json: { data: [queued], next_before: 'm5' } },
        { path: '/app/messages?limit=50&before=m5', json: { data: [msg('m1', { to: '+37060000003' })], next_before: null } },
      ])
      renderApp('/messages')
      await screen.findByRole('button', { name: /queued message/i })
      await user.click(screen.getByRole('button', { name: /load more/i }))
      await screen.findByRole('button', { name: /\+37060000003/ })
      const first = m.callsTo('GET', '/app/messages?limit=50').length
      const second = m.callsTo('GET', '/app/messages?limit=50&before=m5').length
      await act(() => vi.advanceTimersByTimeAsync(5_000))
      await waitFor(() => expect(m.callsTo('GET', '/app/messages?limit=50&before=m5')).toHaveLength(second + 1))
      expect(m.callsTo('GET', '/app/messages?limit=50')).toHaveLength(first + 1)
    })
  })

  describe('?message deep link', () => {
    it('shows no dialog while loading, then opens the listed message', async () => {
      const gate = deferred()
      mockFetch([
        ...base,
        { path: '/app/messages?limit=50', json: { data: [msg(M7, { to: '+37060000077' })] }, until: gate.promise },
      ])
      renderApp(`/messages?message=${M7}`)
      expect(await screen.findByTestId('messages-skeleton')).toBeInTheDocument()
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
      gate.resolve()
      const drawer = await screen.findByRole('dialog')
      expect(within(drawer).getByRole('heading', { name: '+37060000077' })).toBeInTheDocument()
    })

    it('fetches a message that is not in the loaded list', async () => {
      const m = mockFetch([
        ...base,
        { path: '/app/messages?limit=50', json: { data: [msg('m1')] } },
        { path: `/app/messages/${M7}`, json: msg(M7, { to: '+37060000077' }) },
      ])
      renderApp(`/messages?message=${M7}`)
      const drawer = await screen.findByRole('dialog')
      expect(within(drawer).getByRole('heading', { name: '+37060000077' })).toBeInTheDocument()
      expect(m.callsTo('GET', `/app/messages/${M7}`)).toHaveLength(1)
    })

    it('a 404 shows a toast, no dialog, and drops the param', async () => {
      mockFetch([
        ...base,
        { path: '/app/messages?limit=50', json: { data: [msg('m1')] } },
        { path: `/app/messages/${M7}`, ...apiError(404, 'message_not_found') },
      ])
      const { router } = renderApp(`/messages?message=${M7}`)
      expect(await screen.findByText('Message not found')).toBeInTheDocument()
      await waitFor(() => expect(locationOf(router)).toBe('/messages'))
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })

    it('closing the drawer drops the param', async () => {
      const user = userEvent.setup()
      mockFetch([...base, { path: '/app/messages?limit=50', json: { data: [msg(M7)] } }])
      const { router } = renderApp(`/messages?message=${M7}`)
      await screen.findByRole('dialog')
      await user.keyboard('{Escape}')
      await waitFor(() => expect(locationOf(router)).toBe('/messages'))
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    })
  })

  it('?send=true for an unverified user opens nothing and drops the param', async () => {
    mockFetch([
      { path: '/app/me', json: unverifiedMe },
      { path: '/app/devices', json: { data: devices } },
      { path: '/app/messages?limit=50', json: { data: [msg('m1')] } },
    ])
    const { router } = renderApp('/messages?send=true')
    await screen.findByRole('button', { name: /delivered message/i })
    await waitFor(() => expect(locationOf(router)).toBe('/messages'))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})

describe('Messages: device names', () => {
  it('says "Removed device" only once the devices list is loaded', async () => {
    const gate = deferred()
    mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', json: { data: [devices[0]] }, until: gate.promise },
      { path: '/app/messages?limit=50', json: { data: [msg('m1'), msg('m3', { device_id: D2 })] } },
    ])
    renderApp('/messages')

    await screen.findByRole('list', { name: 'Messages' })
    expect(rowsOf()).toHaveLength(2)
    expect(screen.queryByText('Removed device')).not.toBeInTheDocument()
    expect(screen.getAllByText('Device not loaded')).toHaveLength(2)

    gate.resolve()
    expect(await screen.findByText('Removed device')).toBeInTheDocument()
    expect(within(rowsOf()[0]!).getByText('Pixel 8')).toBeInTheDocument()
    expect(screen.queryByText('Device not loaded')).not.toBeInTheDocument()
  })
})
