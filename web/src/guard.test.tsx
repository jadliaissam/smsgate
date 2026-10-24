import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import { apiError, mockFetch } from './test/mockFetch'
import { locationOf, renderApp, verifiedMe } from './test/renderApp'
import { meKey } from './api/queries'

// [Review Focus 1] Session expired: any 401 from /app/* clears `me` and sends
// the user to /login?next=<where they were>.
describe('session guard', () => {
  it('redirects a protected route to /login?next= when /app/me is 401', async () => {
    mockFetch([{ path: '/app/me', ...apiError(401, 'unauthorized') }])
    const { router } = renderApp('/devices')
    await waitFor(() => expect(locationOf(router)).toBe('/login?next=%2Fdevices'))
    expect(await screen.findByRole('button', { name: /sign in/i })).toBeInTheDocument()
  })

  it('keeps the query string of the protected route in `next`', async () => {
    mockFetch([{ path: '/app/me', ...apiError(401, 'unauthorized') }])
    const { router } = renderApp('/api-keys?tab=active')
    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
    expect(router.state.location.search).toEqual({ next: '/api-keys?tab=active' })
  })

  it('a 401 mid-use clears `me` and redirects back to /login?next=<current path>', async () => {
    mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', ...apiError(401, 'unauthorized') },
      { path: '/app/api-keys', json: { data: [] } },
    ])
    const { router, queryClient } = renderApp('/')
    await waitFor(() => expect(locationOf(router)).toBe('/login?next=%2F'))
    expect(queryClient.getQueryData(meKey)).toBeUndefined()
  })

  it('renders the protected page when the session is valid', async () => {
    mockFetch([{ path: '/app/me', json: verifiedMe }])
    renderApp('/devices')
    expect(await screen.findByText(/coming in the next step/i)).toBeInTheDocument()
  })

  it('shows a retryable error page when /app/me fails for another reason', async () => {
    mockFetch([{ path: '/app/me', networkError: true }])
    renderApp('/devices')
    expect(await screen.findByRole('button', { name: /retry/i })).toBeInTheDocument()
  })

  it('unknown routes render the 404 page', async () => {
    mockFetch([{ path: '/app/me', json: verifiedMe }])
    renderApp('/definitely/not/here')
    expect(await screen.findByRole('heading', { name: /page not found/i })).toBeInTheDocument()
  })
})
