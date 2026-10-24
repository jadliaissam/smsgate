import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { mockFetch } from '../test/mockFetch'
import { locationOf, renderApp, unverifiedMe, verifiedMe } from '../test/renderApp'
import { RESEND_COOLDOWN_KEY } from '../api/resendCooldown'

const emptyLists = [
  { path: '/app/devices', json: { data: [] } },
  { path: '/app/api-keys', json: { data: [] } },
]

describe('AppShell', () => {
  it('shows the navigation with the current page marked', async () => {
    mockFetch([{ path: '/app/me', json: verifiedMe }])
    renderApp('/devices')
    const nav = await screen.findByRole('navigation', { name: /main/i })
    const links = within(nav).getAllByRole('link')
    expect(links.map((l) => l.textContent?.trim())).toEqual(['Overview', 'Devices', 'Messages', 'API keys', 'Docs'])
    expect(within(nav).getByRole('link', { name: 'Devices' })).toHaveAttribute('aria-current', 'page')
    const tabs = within(screen.getByRole('navigation', { name: /tab bar/i })).getAllByRole('link')
    expect(tabs.map((l) => l.textContent?.trim())).toEqual(['Overview', 'Devices', 'Messages', 'API keys', 'Docs'])
  })

  it('logout calls POST /app/logout and navigates to /login', async () => {
    const user = userEvent.setup()
    const m = mockFetch([
      { path: '/app/me', json: verifiedMe },
      { method: 'POST', path: '/app/logout', status: 204 },
      ...emptyLists,
    ])
    const { router, queryClient } = renderApp('/')
    await user.click(await screen.findByRole('button', { name: /account menu/i }))
    await user.click(await screen.findByRole('menuitem', { name: /sign out/i }))

    await waitFor(() => expect(locationOf(router)).toBe('/login'))
    const [call] = m.callsTo('POST', '/app/logout')
    expect(call!.headers['content-type']).toBe('application/json')
    // The previous user's data is dropped once the protected pages are gone.
    await waitFor(() => expect(queryClient.getQueryCache().getAll()).toHaveLength(0))
  })

  it('logout clears the resend cooldown so the next user starts fresh', async () => {
    const user = userEvent.setup()
    mockFetch([
      { path: '/app/me', json: verifiedMe },
      { method: 'POST', path: '/app/logout', status: 204 },
      ...emptyLists,
    ])
    sessionStorage.setItem(RESEND_COOLDOWN_KEY, String(Date.now() + 50_000))
    const { router } = renderApp('/')
    await user.click(await screen.findByRole('button', { name: /account menu/i }))
    await user.click(await screen.findByRole('menuitem', { name: /sign out/i }))
    await waitFor(() => expect(locationOf(router)).toBe('/login'))
    expect(sessionStorage.getItem(RESEND_COOLDOWN_KEY)).toBeNull()
  })

  it('shows the unverified banner; resend calls POST /app/resend-verification then cools down', async () => {
    const user = userEvent.setup()
    const m = mockFetch([
      { path: '/app/me', json: unverifiedMe },
      { method: 'POST', path: '/app/resend-verification', status: 204 },
      ...emptyLists,
    ])
    renderApp('/devices')
    const banner = await screen.findByRole('region', { name: /verify your email/i })
    await user.click(within(banner).getByRole('button', { name: /resend email/i }))

    await waitFor(() => expect(m.callsTo('POST', '/app/resend-verification')).toHaveLength(1))
    expect(m.callsTo('POST', '/app/resend-verification')[0]!.headers['content-type']).toBe('application/json')
    expect(await within(banner).findByRole('button', { name: /resend in 60s/i })).toBeDisabled()
    expect(await screen.findByText(/verification email sent/i)).toBeInTheDocument()
  })

  it('the unverified banner can be dismissed and is absent for verified users', async () => {
    const user = userEvent.setup()
    mockFetch([{ path: '/app/me', json: unverifiedMe }])
    renderApp('/devices')
    const banner = await screen.findByRole('region', { name: /verify your email/i })
    await user.click(within(banner).getByRole('button', { name: /dismiss/i }))
    expect(screen.queryByRole('region', { name: /verify your email/i })).not.toBeInTheDocument()
  })

  it('verified users see no banner', async () => {
    mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', json: { data: [] } },
    ])
    renderApp('/devices')
    await screen.findByRole('heading', { level: 1, name: 'Devices' })
    expect(screen.queryByRole('region', { name: /verify your email/i })).not.toBeInTheDocument()
  })
})
