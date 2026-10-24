import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { apiError, mockFetch } from '../test/mockFetch'
import { deferred, renderApp, unverifiedMe, verifiedMe } from '../test/renderApp'
import type { ApiKey, CreatedApiKey } from '../api/types'
import type { QueryClient } from '@tanstack/react-query'

const HOUR = 3_600_000
const FULL_KEY = 'sk_live_n3wK3yS3cr3tV4lu3_d0NotL0g_9f8e7d6c5b4a'

function keys(): ApiKey[] {
  return [
    {
      id: 'k1',
      name: 'Production server',
      prefix: 'sk_live_ab12',
      created_at: new Date(Date.now() - 3 * 24 * HOUR).toISOString(),
      last_used_at: new Date(Date.now() - 2 * HOUR).toISOString(),
      revoked_at: null,
    },
    {
      id: 'k2',
      name: 'Staging',
      prefix: 'sk_live_cd34',
      created_at: new Date(Date.now() - 5 * HOUR).toISOString(),
      last_used_at: null,
      revoked_at: null,
    },
    {
      id: 'k3',
      name: 'Old laptop',
      prefix: 'sk_live_ef56',
      created_at: new Date(Date.now() - 40 * 24 * HOUR).toISOString(),
      last_used_at: null,
      revoked_at: new Date(Date.now() - 10 * 24 * HOUR).toISOString(),
    },
  ]
}

const created: CreatedApiKey = {
  id: 'k9',
  name: 'Billing worker',
  prefix: FULL_KEY.slice(0, 12),
  key: FULL_KEY,
  created_at: new Date().toISOString(),
  last_used_at: null,
  revoked_at: null,
}
const { key: _omit, ...createdListed } = created
void _omit

function row(name: string | RegExp) {
  return screen.getByRole('listitem', { name })
}

/** Everything the QueryClient holds (queries and mutations), serialised. */
function cacheDump(qc: QueryClient): string {
  return JSON.stringify([
    qc.getQueryCache().getAll().map((q) => q.state.data),
    qc.getMutationCache().getAll().map((m) => [m.state.data, m.state.variables]),
  ])
}

describe('API keys', () => {
  it('lists keys with prefix, created, last used and muted revoked keys', async () => {
    mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/api-keys', json: { data: keys() } },
    ])
    renderApp('/api-keys')

    const prod = await screen.findByRole('listitem', { name: 'Production server' })
    expect(within(prod).getByText('sk_live_ab12…')).toHaveClass('font-mono')
    expect(within(prod).getByText('3 days ago')).toBeInTheDocument()
    expect(within(prod).getByText('2 hours ago')).toBeInTheDocument()
    expect(within(prod).getByRole('button', { name: /revoke production server/i })).toBeInTheDocument()

    expect(within(row('Staging')).getByText('Never')).toBeInTheDocument()

    const revoked = row('Old laptop')
    expect(revoked).toHaveAttribute('data-revoked', 'true')
    expect(within(revoked).getByText(/revoked/i)).toBeInTheDocument()
    expect(within(revoked).queryByRole('button', { name: /revoke/i })).not.toBeInTheDocument()
  })

  it('shows a skeleton while loading, then an error state whose Retry refetches', async () => {
    const user = userEvent.setup()
    const gate = deferred()
    const m = mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/api-keys', ...apiError(500, 'internal'), once: true, until: gate.promise },
      { path: '/app/api-keys', json: { data: keys() } },
    ])
    renderApp('/api-keys')

    expect(await screen.findByTestId('api-keys-skeleton')).toBeInTheDocument()
    gate.resolve()
    await user.click(await screen.findByRole('button', { name: /retry/i }))
    expect(await screen.findByRole('listitem', { name: 'Staging' })).toBeInTheDocument()
    expect(m.callsTo('GET', '/app/api-keys')).toHaveLength(2)
  })

  it('shows an empty state with a create CTA', async () => {
    mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/api-keys', json: { data: [] } },
    ])
    renderApp('/api-keys')
    expect(await screen.findByRole('button', { name: /create your first key/i })).toBeEnabled()
  })

  // [Review Focus 2] The full key appears once, only in the modal, never in
  // the query cache; closing the modal drops it.
  it('creates a key, shows the full key once, and forgets it on close', async () => {
    const user = userEvent.setup()
    const m = mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/api-keys', json: { data: keys() }, once: true },
      { path: '/app/api-keys', json: { data: [createdListed, ...keys()] } },
      { method: 'POST', path: '/app/api-keys', status: 201, json: created },
    ])
    const { queryClient } = renderApp('/api-keys')

    await user.click(await screen.findByRole('button', { name: /create key/i }))
    let dialog = await screen.findByRole('dialog', { name: /create an api key/i })
    await user.type(within(dialog).getByLabelText(/name/i), '  Billing worker  ')
    await user.click(within(dialog).getByRole('button', { name: /create key/i }))

    // Revealed with a copy button and the warning.
    expect(await within(dialog).findByText(FULL_KEY)).toHaveClass('font-mono')
    expect(within(dialog).getByText(/you won.t see this again/i)).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: /copy/i })).toBeInTheDocument()
    expect(m.callsTo('POST', '/app/api-keys')[0]!.body).toEqual({ name: 'Billing worker' })
    expect(m.callsTo('POST', '/app/api-keys')[0]!.headers['content-type']).toBe('application/json')

    // The list is refetched (prefix only) and nothing cached holds the key.
    await waitFor(() => expect(m.callsTo('GET', '/app/api-keys')).toHaveLength(2))
    expect(await screen.findByRole('listitem', { name: 'Billing worker' })).toBeInTheDocument()
    await waitFor(() => expect(cacheDump(queryClient)).not.toContain(FULL_KEY))
    expect(screen.getAllByText(FULL_KEY)).toHaveLength(1)

    await user.click(within(dialog).getByRole('button', { name: /done/i }))
    // Wiped at once, not only after the exit animation.
    expect(screen.queryByText(FULL_KEY)).not.toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: /create key/i }))
    dialog = await screen.findByRole('dialog', { name: /create an api key/i })
    expect(within(dialog).getByLabelText(/name/i)).toHaveValue('')
    expect(screen.queryByText(FULL_KEY)).not.toBeInTheDocument()
    expect(cacheDump(queryClient)).not.toContain(FULL_KEY)
  })

  it('Esc closes the reveal and wipes the key too', async () => {
    const user = userEvent.setup()
    mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/api-keys', json: { data: [] } },
      { method: 'POST', path: '/app/api-keys', status: 201, json: created },
    ])
    renderApp('/api-keys')

    await user.click(await screen.findByRole('button', { name: /create key/i }))
    const dialog = await screen.findByRole('dialog', { name: /create an api key/i })
    await user.type(within(dialog).getByLabelText(/name/i), 'Billing worker{Enter}')
    await within(dialog).findByText(FULL_KEY)
    await user.keyboard('{Escape}')
    expect(screen.queryByText(FULL_KEY)).not.toBeInTheDocument()
  })

  it('shows invalid_name on the name field', async () => {
    const user = userEvent.setup()
    mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/api-keys', json: { data: [] } },
      { method: 'POST', path: '/app/api-keys', ...apiError(422, 'invalid_name') },
    ])
    renderApp('/api-keys')

    await user.click(await screen.findByRole('button', { name: /create key/i }))
    const dialog = await screen.findByRole('dialog', { name: /create an api key/i })
    const input = within(dialog).getByLabelText(/name/i)
    await user.type(input, 'x{Enter}')
    await waitFor(() => expect(input).toHaveAttribute('aria-invalid', 'true'))
    expect(input).toHaveAccessibleDescription(/between 1 and 64 characters/i)
  })

  it('revokes optimistically after a danger confirm', async () => {
    const user = userEvent.setup()
    const gate = deferred()
    const revokedAt = new Date().toISOString()
    const m = mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/api-keys', json: { data: keys() }, once: true },
      {
        path: '/app/api-keys',
        json: { data: keys().map((k) => (k.id === 'k1' ? { ...k, revoked_at: revokedAt } : k)) },
      },
      { method: 'DELETE', path: '/app/api-keys/k1', status: 204, until: gate.promise },
    ])
    renderApp('/api-keys')

    await user.click(await screen.findByRole('button', { name: /revoke production server/i }))
    const dialog = await screen.findByRole('dialog', { name: /revoke production server/i })
    await user.click(within(dialog).getByRole('button', { name: /revoke key/i }))

    // Shown as revoked before the server answers.
    await waitFor(() => expect(row('Production server')).toHaveAttribute('data-revoked', 'true'))
    expect(within(row('Production server')).queryByRole('button', { name: /revoke/i })).not.toBeInTheDocument()
    expect(m.callsTo('DELETE', '/app/api-keys/k1')[0]!.headers['content-type']).toBe('application/json')
    gate.resolve()
    await waitFor(() => expect(m.callsTo('GET', '/app/api-keys')).toHaveLength(2))
    expect(row('Production server')).toHaveAttribute('data-revoked', 'true')
  })

  it('a failed revoke rolls back and shows an error toast', async () => {
    const user = userEvent.setup()
    const gate = deferred()
    mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/api-keys', json: { data: keys() } },
      { method: 'DELETE', path: '/app/api-keys/k1', ...apiError(500, 'internal'), until: gate.promise },
    ])
    renderApp('/api-keys')

    await user.click(await screen.findByRole('button', { name: /revoke production server/i }))
    const dialog = await screen.findByRole('dialog', { name: /revoke production server/i })
    await user.click(within(dialog).getByRole('button', { name: /revoke key/i }))

    await waitFor(() => expect(row('Production server')).toHaveAttribute('data-revoked', 'true'))
    gate.resolve()
    await waitFor(() => expect(row('Production server')).toHaveAttribute('data-revoked', 'false'))
    const errors = screen.getByRole('region', { name: 'Errors' })
    expect(await within(errors).findByText(/couldn.t revoke production server/i)).toBeInTheDocument()
  })

  it('unverified users cannot create keys: disabled button with a reason and a resend action', async () => {
    const user = userEvent.setup()
    mockFetch([
      { path: '/app/me', json: unverifiedMe },
      { path: '/app/api-keys', json: { data: [] } },
    ])
    renderApp('/api-keys')

    const create = await screen.findByRole('button', { name: /^create key/i })
    expect(create).toHaveAttribute('aria-disabled', 'true')
    expect(create).toHaveAccessibleDescription(/verify your email/i)
    await user.click(create)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    const notice = screen.getByRole('region', { name: /verify your email to create api keys/i })
    expect(within(notice).getByRole('button', { name: /resend email/i })).toBeInTheDocument()
  })

  it('a 403 email_not_verified from the server shows the verify prompt and refreshes the session', async () => {
    const user = userEvent.setup()
    const m = mockFetch([
      { path: '/app/me', json: verifiedMe, once: true },
      { path: '/app/me', json: unverifiedMe },
      { path: '/app/api-keys', json: { data: [] } },
      { method: 'POST', path: '/app/api-keys', ...apiError(403, 'email_not_verified') },
    ])
    renderApp('/api-keys')

    await user.click(await screen.findByRole('button', { name: /create key/i }))
    const dialog = await screen.findByRole('dialog', { name: /create an api key/i })
    await user.type(within(dialog).getByLabelText(/name/i), 'Billing worker{Enter}')

    expect(await within(dialog).findByText(/verify your email first/i)).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: /resend email/i })).toBeInTheDocument()
    await waitFor(() => expect(m.callsTo('GET', '/app/me').length).toBeGreaterThanOrEqual(2))
  })
})
