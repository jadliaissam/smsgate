import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { apiError, mockFetch } from '../test/mockFetch'
import { renderApp, unverifiedMe, verifiedMe } from '../test/renderApp'
import type { Device, PairingCode } from '../api/types'

const existing: Device = {
  id: 'd1',
  name: 'Pixel 8',
  online: true,
  sims: [],
  battery: 80,
  charging: false,
  last_seen_at: new Date().toISOString(),
}
const paired: Device = { ...existing, id: 'd2', name: 'Galaxy A55' }

function pairingCode(ttlMs: number, code = 'ABCD2345'): PairingCode {
  return {
    code,
    expires_at: new Date(Date.now() + ttlMs).toISOString(),
    qr_payload: `smsgate://pair?server=https%3A%2F%2Fsmsgate.example&code=${code}`,
  }
}

async function openPairModal() {
  const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
  await user.click(await screen.findByRole('button', { name: /^pair a phone$/i }))
  const dialog = await screen.findByRole('dialog', { name: /pair a phone/i })
  return { user, dialog }
}

// [Review Focus 3] Countdown to expires_at, "Generate new code", and
// detection of the new device by polling /app/devices every 2 s.
describe('PairModal', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('shows the QR and grouped code; Copy copies the raw code', async () => {
    const m = mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', json: { data: [existing] } },
      { method: 'POST', path: '/app/pairing-codes', status: 201, json: pairingCode(300_000) },
    ])
    renderApp('/devices')
    const { user, dialog } = await openPairModal()

    expect(await within(dialog).findByText('ABCD-2345')).toBeInTheDocument()
    expect(within(dialog).getByRole('img', { name: /pairing qr code/i })).toBeInTheDocument()
    expect(within(dialog).getByRole('timer')).toHaveTextContent('5:00')
    expect(m.callsTo('POST', '/app/pairing-codes')).toHaveLength(1)

    await user.click(within(dialog).getByRole('button', { name: /copy code/i }))
    await expect(navigator.clipboard.readText()).resolves.toBe('ABCD2345')
  })

  it('counts down to expires_at, then offers "Generate new code"', async () => {
    const m = mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', json: { data: [existing] } },
      { method: 'POST', path: '/app/pairing-codes', status: 201, json: pairingCode(300_000), once: true },
      { method: 'POST', path: '/app/pairing-codes', status: 201, json: pairingCode(300_000, 'WXYZ6789') },
    ])
    renderApp('/devices')
    const { user, dialog } = await openPairModal()
    await within(dialog).findByText('ABCD-2345')

    act(() => vi.advanceTimersByTime(60_000))
    expect(within(dialog).getByRole('timer')).toHaveTextContent('4:00')
    expect(within(dialog).queryByRole('button', { name: /generate new code/i })).not.toBeInTheDocument()

    act(() => vi.advanceTimersByTime(241_000))
    const regenerate = await within(dialog).findByRole('button', { name: /generate new code/i })
    expect(within(dialog).getByText(/code expired/i)).toBeInTheDocument()

    await user.click(regenerate)
    expect(await within(dialog).findByText('WXYZ-6789')).toBeInTheDocument()
    expect(m.callsTo('POST', '/app/pairing-codes')).toHaveLength(2)
  })

  it('polls devices every 2 s; a new device shows success, toasts and closes after 1.5 s', async () => {
    const m = mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', json: { data: [existing] } },
      { method: 'POST', path: '/app/pairing-codes', status: 201, json: pairingCode(300_000) },
    ])
    renderApp('/devices')
    const { dialog } = await openPairModal()
    await within(dialog).findByText('ABCD-2345')
    const before = m.callsTo('GET', '/app/devices').length

    await act(async () => vi.advanceTimersByTimeAsync(2_000))
    await waitFor(() => expect(m.callsTo('GET', '/app/devices').length).toBeGreaterThan(before))

    // The phone registers: the next poll sees a device id that was not there at open time.
    m.use({ path: '/app/devices', json: { data: [existing, paired] } })
    await act(async () => vi.advanceTimersByTimeAsync(2_000))

    expect(await within(dialog).findByRole('heading', { name: /galaxy a55 is paired/i })).toBeInTheDocument()
    const notifications = screen.getByRole('region', { name: 'Notifications' })
    expect(within(notifications).getByText('Galaxy A55 paired')).toBeInTheDocument()

    await act(async () => vi.advanceTimersByTimeAsync(1_600))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(screen.getByRole('article', { name: 'Galaxy A55' })).toBeInTheDocument()

    // Polling stops once the modal is closed.
    const afterClose = m.callsTo('GET', '/app/devices').length
    await act(async () => vi.advanceTimersByTimeAsync(6_000))
    expect(m.callsTo('GET', '/app/devices')).toHaveLength(afterClose)
  })

  it('unverified email: shows a verify prompt with resend', async () => {
    const m = mockFetch([
      { path: '/app/me', json: unverifiedMe },
      { path: '/app/devices', json: { data: [] } },
      { method: 'POST', path: '/app/pairing-codes', ...apiError(403, 'email_not_verified') },
      { method: 'POST', path: '/app/resend-verification', status: 204 },
    ])
    renderApp('/devices')
    const { user, dialog } = await openPairModal()

    expect(await within(dialog).findByText(/verify your email to pair a phone/i)).toBeInTheDocument()
    expect(within(dialog).queryByRole('img', { name: /pairing qr code/i })).not.toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: /resend email/i }))
    await waitFor(() => expect(m.callsTo('POST', '/app/resend-verification')).toHaveLength(1))
  })
})
