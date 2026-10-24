import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { apiError, mockFetch } from '../test/mockFetch'
import { deferred, renderApp, unverifiedMe, verifiedMe } from '../test/renderApp'
import type { ApiKey, Device } from '../api/types'

const device: Device = {
  id: '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b',
  name: 'Pixel 8',
  online: true,
  sims: [],
  battery: 80,
  charging: false,
  last_seen_at: '2026-10-24T08:00:00Z',
}
const revokedKey: ApiKey = {
  id: 'k1',
  name: 'old',
  prefix: 'sk_live_abcd',
  created_at: '2026-10-01T00:00:00Z',
  last_used_at: null,
  revoked_at: '2026-10-02T00:00:00Z',
}

function step(name: RegExp) {
  return screen.getByRole('listitem', { name })
}

// [Review Focus 4] Skeleton while loading, error state with Retry.
describe('Overview', () => {
  it('shows skeletons first, then the checklist computed from me, devices and keys', async () => {
    const gate = deferred()
    mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', json: { data: [device] }, until: gate.promise },
      { path: '/app/api-keys', json: { data: [revokedKey] }, until: gate.promise },
    ])
    renderApp('/')

    expect(await screen.findByTestId('checklist-skeleton')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(/ada/i)
    gate.resolve()

    expect(await screen.findByText(/2 of 3 done/i)).toBeInTheDocument()
    expect(screen.queryByTestId('checklist-skeleton')).not.toBeInTheDocument()
    expect(step(/verify your email/i)).toHaveAttribute('data-done', 'true')
    expect(step(/pair a phone/i)).toHaveAttribute('data-done', 'true')
    // A revoked key does not count.
    expect(step(/create an api key/i)).toHaveAttribute('data-done', 'false')
    expect(within(step(/create an api key/i)).getByRole('link', { name: /create a key/i })).toHaveAttribute(
      'href',
      '/api-keys',
    )
  })

  it('unverified with nothing set up: 0 of 3, links to each page, curl uses the origin', async () => {
    mockFetch([
      { path: '/app/me', json: unverifiedMe },
      { path: '/app/devices', json: { data: [] } },
      { path: '/app/api-keys', json: { data: [] } },
    ])
    renderApp('/')

    expect(await screen.findByText(/0 of 3 done/i)).toBeInTheDocument()
    expect(step(/verify your email/i)).toHaveAttribute('data-done', 'false')
    expect(within(step(/pair a phone/i)).getByRole('link', { name: /pair a phone/i })).toHaveAttribute(
      'href',
      '/devices',
    )
    const send = step(/send your first sms/i)
    expect(within(send).getByText(new RegExp(`${window.location.origin}/v1/messages`))).toBeInTheDocument()
    expect(within(send).getByRole('button', { name: /copy/i })).toBeInTheDocument()
  })

  it('fills the curl snippet with the first device id when one exists', async () => {
    mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', json: { data: [device] } },
      { path: '/app/api-keys', json: { data: [] } },
    ])
    renderApp('/')
    await screen.findByText(/2 of 3 done/i)
    expect(within(step(/send your first sms/i)).getByText(new RegExp(device.id))).toBeInTheDocument()
  })

  it('shows an error state and Retry refetches', async () => {
    const user = userEvent.setup()
    const m = mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', ...apiError(500, 'internal'), once: true },
      { path: '/app/devices', json: { data: [] } },
      { path: '/app/api-keys', json: { data: [] } },
    ])
    renderApp('/')

    const retry = await screen.findByRole('button', { name: /retry/i })
    expect(screen.getByText(/couldn.t load your setup/i)).toBeInTheDocument()
    await user.click(retry)
    expect(await screen.findByText(/1 of 3 done/i)).toBeInTheDocument()
    await waitFor(() => expect(m.callsTo('GET', '/app/devices')).toHaveLength(2))
  })
})
