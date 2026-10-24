import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { apiError, mockFetch } from '../test/mockFetch'
import { locationOf, renderApp, verifiedMe } from '../test/renderApp'
import { errorCopy } from '../api/errors'
import { devicesKey, meKey } from '../api/queries'

async function fillAndSubmit(email = 'ada@example.com', password = 'correct horse battery') {
  const user = userEvent.setup()
  await user.type(await screen.findByLabelText('Email'), email)
  await user.type(screen.getByLabelText('Password'), password)
  await user.click(screen.getByRole('button', { name: /sign in/i }))
}

describe('Login', () => {
  it('posts JSON credentials and navigates to `next` on success', async () => {
    const m = mockFetch([
      { method: 'POST', path: '/app/login', status: 204 },
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', json: { data: [] } },
    ])
    const { router } = renderApp('/login?next=%2Fdevices')
    await fillAndSubmit()

    await waitFor(() => expect(locationOf(router)).toBe('/devices'))
    const [call] = m.callsTo('POST', '/app/login')
    expect(call!.headers['content-type']).toBe('application/json')
    expect(call!.body).toEqual({ email: 'ada@example.com', password: 'correct horse battery' })
    expect(await screen.findByRole('heading', { level: 1, name: 'Devices' })).toBeInTheDocument()
  })

  it('shows mapped copy for invalid credentials and stays on /login', async () => {
    mockFetch([{ method: 'POST', path: '/app/login', ...apiError(401, 'invalid_credentials') }])
    const { router } = renderApp('/login')
    await fillAndSubmit()

    expect(await screen.findByText(errorCopy('invalid_credentials'))).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
    expect(screen.getByRole('button', { name: /sign in/i })).toBeEnabled()
  })

  it('ignores an off-site `next` (open redirect) and goes home', async () => {
    mockFetch([
      { method: 'POST', path: '/app/login', status: 204 },
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', json: { data: [] } },
      { path: '/app/api-keys', json: { data: [] } },
    ])
    const { router } = renderApp('/login?next=%2F%2Fevil.example')
    await fillAndSubmit()
    await waitFor(() => expect(locationOf(router)).toBe('/'))
  })

  it('drops data cached for a previous user on login success', async () => {
    mockFetch([
      { method: 'POST', path: '/app/login', status: 204 },
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', json: { data: [] } },
    ])
    const { router, queryClient } = renderApp('/login?next=%2Fdevices')
    // Left behind by someone else's session (fresh, so it would be shown as is).
    queryClient.setQueryData(meKey, { ...verifiedMe, id: 'u0', email: 'previous@example.com' })
    queryClient.setQueryData(devicesKey, [
      { id: 'd0', name: 'Previous user phone', online: true, sims: [], battery: 50, charging: false, last_seen_at: null },
    ])
    await fillAndSubmit()

    await waitFor(() => expect(locationOf(router)).toBe('/devices'))
    expect(await screen.findByRole('button', { name: /pair your first phone/i })).toBeInTheDocument()
    expect(screen.queryByText('Previous user phone')).not.toBeInTheDocument()
    expect(screen.queryByText('previous@example.com')).not.toBeInTheDocument()
    expect(queryClient.getQueryData(meKey)).toEqual(verifiedMe)
  })

  it('does not keep the password in the mutation cache after success', async () => {
    mockFetch([
      { method: 'POST', path: '/app/login', status: 204 },
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', json: { data: [] } },
    ])
    const { router, queryClient } = renderApp('/login?next=%2Fdevices')
    await fillAndSubmit()
    await waitFor(() => expect(locationOf(router)).toBe('/devices'))
    await waitFor(() =>
      expect(queryClient.getMutationCache().getAll().map((m) => m.state.variables)).not.toContainEqual(
        expect.objectContaining({ password: expect.anything() }),
      ),
    )
  })

  it('links to sign up', async () => {
    mockFetch([])
    renderApp('/login')
    expect(await screen.findByRole('link', { name: /create an account/i })).toHaveAttribute('href', '/signup')
  })
})
