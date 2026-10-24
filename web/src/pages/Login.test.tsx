import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { apiError, mockFetch } from '../test/mockFetch'
import { locationOf, renderApp, verifiedMe } from '../test/renderApp'
import { errorCopy } from '../api/errors'

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

  it('links to sign up', async () => {
    mockFetch([])
    renderApp('/login')
    expect(await screen.findByRole('link', { name: /create an account/i })).toHaveAttribute('href', '/signup')
  })
})
