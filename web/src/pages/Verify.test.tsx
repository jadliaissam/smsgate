import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import { apiError, mockFetch } from '../test/mockFetch'
import { renderApp, unverifiedMe } from '../test/renderApp'
import { errorCopy } from '../api/errors'

describe('Verify', () => {
  it('consumes the token once and shows the success state', async () => {
    const m = mockFetch([
      { method: 'POST', path: '/app/verify', status: 204 },
      { path: '/app/me', ...apiError(401, 'unauthorized') },
    ])
    renderApp('/verify?token=tok_123')

    expect(await screen.findByRole('heading', { name: /email verified/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /continue/i })).toHaveAttribute('href', '/')
    const calls = m.callsTo('POST', '/app/verify')
    expect(calls).toHaveLength(1)
    expect(calls[0]!.body).toEqual({ token: 'tok_123' })
  })

  it('invalid_token shows the error state with a way to get a new link (signed out)', async () => {
    mockFetch([
      { method: 'POST', path: '/app/verify', ...apiError(400, 'invalid_token') },
      { path: '/app/me', ...apiError(401, 'unauthorized') },
    ])
    const { router } = renderApp('/verify?token=expired')

    expect(await screen.findByText(errorCopy('invalid_token'))).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: /sign in to get a new link/i })).toHaveAttribute(
      'href',
      '/login?next=%2F',
    )
    // A 401 from /app/me on a public page must not bounce the user away.
    expect(router.state.location.pathname).toBe('/verify')
  })

  it('invalid_token offers an inline resend when signed in', async () => {
    mockFetch([
      { method: 'POST', path: '/app/verify', ...apiError(400, 'invalid_token') },
      { path: '/app/me', json: unverifiedMe },
    ])
    renderApp('/verify?token=expired')
    expect(await screen.findByRole('button', { name: /send a new link/i })).toBeEnabled()
  })

  it('without a token shows the error state without calling the API', async () => {
    const m = mockFetch([{ path: '/app/me', ...apiError(401, 'unauthorized') }])
    renderApp('/verify')
    expect(await screen.findByText(errorCopy('invalid_token'))).toBeInTheDocument()
    expect(m.callsTo('POST', '/app/verify')).toHaveLength(0)
  })
})
