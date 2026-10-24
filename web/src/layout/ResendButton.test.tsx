import { describe, expect, it } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createQueryClient } from '../api/queries'
import { mockFetch } from '../test/mockFetch'
import { ToastProvider } from '../ui/Toast'
import { ResendButton } from './ResendButton'
import { RESEND_COOLDOWN_KEY } from './resendCooldown'

function renderButtons(count: number) {
  const qc = createQueryClient({ onUnauthorized: () => {}, retry: false })
  return render(
    <QueryClientProvider client={qc}>
      <ToastProvider>
        {Array.from({ length: count }, (_, i) => (
          <ResendButton key={i} label={`Resend ${i}`} />
        ))}
      </ToastProvider>
    </QueryClientProvider>,
  )
}

describe('ResendButton cooldown', () => {
  it('is shared: resending from one button cools down every button', async () => {
    const user = userEvent.setup()
    const m = mockFetch([{ method: 'POST', path: '/app/resend-verification', status: 204 }])
    renderButtons(2)

    await user.click(screen.getByRole('button', { name: 'Resend 0' }))
    await waitFor(() => expect(screen.getAllByRole('button', { name: /resend in 60s/i })).toHaveLength(2))
    for (const b of screen.getAllByRole('button', { name: /resend in/i })) expect(b).toBeDisabled()
    expect(m.callsTo('POST', '/app/resend-verification')).toHaveLength(1)
  })

  it('survives a remount (persisted in sessionStorage)', async () => {
    const user = userEvent.setup()
    mockFetch([{ method: 'POST', path: '/app/resend-verification', status: 204 }])
    const first = renderButtons(1)
    await user.click(screen.getByRole('button', { name: 'Resend 0' }))
    await screen.findByRole('button', { name: /resend in 60s/i })
    expect(Number(sessionStorage.getItem(RESEND_COOLDOWN_KEY))).toBeGreaterThan(Date.now())
    first.unmount()

    renderButtons(1)
    expect(screen.getByRole('button', { name: /resend in \d+s/i })).toBeDisabled()
  })

  it('picks up a cooldown that is already in sessionStorage', () => {
    sessionStorage.setItem(RESEND_COOLDOWN_KEY, String(Date.now() + 30_000))
    renderButtons(1)
    expect(screen.getByRole('button', { name: /resend in (30|29)s/i })).toBeDisabled()
  })

  it('ignores an expired or garbage stored value', () => {
    sessionStorage.setItem(RESEND_COOLDOWN_KEY, 'nope')
    const r = renderButtons(1)
    expect(screen.getByRole('button', { name: 'Resend 0' })).toBeEnabled()
    r.unmount()
    sessionStorage.setItem(RESEND_COOLDOWN_KEY, String(Date.now() - 1000))
    renderButtons(1)
    expect(screen.getByRole('button', { name: 'Resend 0' })).toBeEnabled()
  })
})
