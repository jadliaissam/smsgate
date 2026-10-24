import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { apiError, mockFetch } from '../test/mockFetch'
import { renderApp } from '../test/renderApp'
import { errorCopy } from '../api/errors'

async function fillAndSubmit(password = 'short') {
  const user = userEvent.setup()
  await user.type(await screen.findByLabelText('Email'), 'ada@example.com')
  await user.type(screen.getByLabelText('Password'), password)
  await user.click(screen.getByRole('button', { name: /create account/i }))
  return user
}

describe('Signup', () => {
  it('shows weak_password as a field error on the password input', async () => {
    mockFetch([{ method: 'POST', path: '/app/signup', ...apiError(422, 'weak_password') }])
    renderApp('/signup')
    await fillAndSubmit()

    const password = screen.getByLabelText('Password')
    await screen.findByText(errorCopy('weak_password'))
    expect(password).toHaveAttribute('aria-invalid', 'true')
    expect(password).toHaveAccessibleDescription(errorCopy('weak_password'))
    expect(screen.getByLabelText('Email')).not.toHaveAttribute('aria-invalid')
  })

  it('shows email_taken on the email field', async () => {
    mockFetch([{ method: 'POST', path: '/app/signup', ...apiError(409, 'email_taken') }])
    renderApp('/signup')
    await fillAndSubmit('a long enough passphrase')
    expect(await screen.findByText(errorCopy('email_taken'))).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toHaveAttribute('aria-invalid', 'true')
  })

  it('201 signs in and shows the check-inbox state with a cooling-down resend', async () => {
    const m = mockFetch([
      { method: 'POST', path: '/app/signup', status: 201, json: { id: 'u1', email: 'ada@example.com' } },
      { method: 'POST', path: '/app/login', status: 204 },
    ])
    renderApp('/signup')
    await fillAndSubmit('a long enough passphrase')

    expect(await screen.findByRole('heading', { name: /check your inbox/i })).toBeInTheDocument()
    expect(screen.getByText('ada@example.com')).toBeInTheDocument()
    // The link was just sent: resend starts on its 60 s cooldown.
    expect(await screen.findByRole('button', { name: /resend in 60s/i })).toBeDisabled()
    expect(m.callsTo('POST', '/app/signup')[0]!.body).toEqual({
      email: 'ada@example.com',
      password: 'a long enough passphrase',
    })
    expect(m.callsTo('POST', '/app/login')).toHaveLength(1)
    expect(screen.getByRole('link', { name: /continue to dashboard/i })).toHaveAttribute('href', '/')
  })
})
