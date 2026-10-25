import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { mockFetch, type MockRoute } from '../test/mockFetch'
import { renderApp, verifiedMe } from '../test/renderApp'
import type { Device } from '../api/types'

const DEVICE_ID = '0192f1a4-7c3e-7b52-9a10-3f5d8e2c4b61'

const devices: Device[] = [
  {
    id: DEVICE_ID,
    name: 'Pixel 8',
    online: true,
    sims: [{ slot: 0, subscription_id: 1, carrier: 'Telia' }],
    battery: 82,
    charging: false,
    last_seen_at: new Date().toISOString(),
  },
  {
    id: '0192f1a4-0000-7000-8000-000000000002',
    name: 'Backup phone',
    online: false,
    sims: [],
    battery: null,
    charging: null,
    last_seen_at: null,
  },
]

const apkMissing: MockRoute = { method: 'HEAD', path: '/downloads/smsgate.apk', status: 404 }

function setup(extra: MockRoute[] = [], list: Device[] = devices) {
  return mockFetch([
    ...extra,
    { path: '/app/me', json: verifiedMe },
    { path: '/app/devices', json: { data: list } },
    apkMissing,
  ])
}

/** The first code sample on the page (the Quickstart send request). */
async function firstSample() {
  const [list] = await screen.findAllByRole('tablist', { name: /language/i })
  const panelId = within(list!).getByRole('tab', { selected: true }).getAttribute('aria-controls')!
  return { list: list!, panel: () => document.getElementById(panelId)! }
}

describe('Docs', () => {
  it('prefills the curl sample with this origin and the first device id', async () => {
    setup()
    renderApp('/docs')
    expect(await screen.findByRole('heading', { level: 1, name: /api docs/i })).toBeInTheDocument()
    const { list, panel } = await firstSample()
    expect(within(list).getByRole('tab', { name: 'curl' })).toHaveAttribute('aria-selected', 'true')
    await waitFor(() => expect(panel().textContent).toContain(DEVICE_ID))
    expect(panel().textContent).toContain(`${window.location.origin}/v1/messages`)
    expect(panel().textContent).toContain('Authorization: Bearer YOUR_API_KEY')
    expect(panel().textContent).toContain('"ttl_seconds": 300')
    expect(panel().textContent).not.toContain('YOUR_DEVICE_ID')
  })

  it('switches the sample language by click and with the arrow keys', async () => {
    const user = userEvent.setup()
    setup()
    renderApp('/docs')
    const { list } = await firstSample()

    await user.click(within(list).getByRole('tab', { name: 'Python' }))
    const python = within(list).getByRole('tab', { name: 'Python' })
    expect(python).toHaveAttribute('aria-selected', 'true')
    expect(python).toHaveAttribute('tabindex', '0')
    expect(within(list).getByRole('tab', { name: 'curl' })).toHaveAttribute('tabindex', '-1')
    const pyPanel = document.getElementById(python.getAttribute('aria-controls')!)!
    expect(pyPanel.textContent).toContain('requests.post(')
    expect(pyPanel.textContent).not.toContain('curl -X POST')

    // Arrow keys move the selection (wrapping) and focus with it.
    await user.keyboard('{ArrowLeft}')
    const js = within(list).getByRole('tab', { name: 'JavaScript' })
    expect(js).toHaveAttribute('aria-selected', 'true')
    expect(js).toHaveFocus()
    expect(document.getElementById(js.getAttribute('aria-controls')!)!.textContent).toContain('await fetch(')
    await user.keyboard('{ArrowRight}{ArrowRight}')
    expect(within(list).getByRole('tab', { name: 'curl' })).toHaveAttribute('aria-selected', 'true')
    await user.keyboard('{End}')
    expect(within(list).getByRole('tab', { name: 'Python' })).toHaveFocus()

    // The choice applies to every sample on the page.
    for (const other of screen.getAllByRole('tablist', { name: /language/i })) {
      expect(within(other).getByRole('tab', { name: 'Python' })).toHaveAttribute('aria-selected', 'true')
    }
  })

  it('uses a placeholder device id and links to Devices when no phone is paired', async () => {
    setup([], [])
    renderApp('/docs')
    const { panel } = await firstSample()
    await waitFor(() => expect(panel().textContent).toContain('"device_id": "YOUR_DEVICE_ID"'))
    expect(screen.getByRole('link', { name: /pair a phone/i })).toHaveAttribute('href', '/devices')
  })

  it('documents the endpoints and the error codes', async () => {
    setup()
    renderApp('/docs')
    await screen.findByRole('heading', { level: 1, name: /api docs/i })
    for (const name of [/send a message/i, /get a message/i, /list messages/i, /list devices/i, /errors/i, /rate limits/i]) {
      expect(screen.getByRole('heading', { level: 2, name })).toBeInTheDocument()
    }
    for (const code of ['invalid_to', 'body_too_long', 'invalid_ttl', 'idempotency_mismatch', 'rate_limited', 'device_not_found']) {
      expect(screen.getAllByText(code).length).toBeGreaterThan(0)
    }
    expect(screen.getByText('next_before', { selector: 'code' })).toBeInTheDocument()
  })

  it('offers the APK download when the file exists', async () => {
    const m = setup([
      {
        method: 'HEAD',
        path: '/downloads/smsgate.apk',
        headers: { 'content-type': 'application/vnd.android.package-archive', 'content-length': '7340032' },
      },
    ])
    renderApp('/docs')
    const link = await screen.findByRole('link', { name: /download apk/i })
    expect(link).toHaveAttribute('href', '/downloads/smsgate.apk')
    expect(screen.getByText(/7\.0 MB/)).toBeInTheDocument()
    expect(m.callsTo('HEAD', '/downloads/smsgate.apk')).toHaveLength(1)
  })

  it('offers the APK when nginx redirects to the latest release, without a size', async () => {
    const m = setup([{ method: 'HEAD', path: '/downloads/smsgate.apk', opaqueRedirect: true }])
    renderApp('/docs')
    const link = await screen.findByRole('link', { name: /download apk/i })
    expect(link).toHaveAttribute('href', '/downloads/smsgate.apk')
    const card = screen.getByRole('region', { name: /android app/i })
    expect(within(card).queryByText(/\bMB\b/)).not.toBeInTheDocument()
    expect(within(card).queryByText(/not available yet/i)).not.toBeInTheDocument()
    expect(card).toHaveTextContent(/Android 7\.0 or later/)
    expect(card).toHaveTextContent(/Allow installs from this source/)
    expect(card).toHaveTextContent(`${window.location.origin}/downloads/smsgate.apk`)
    expect(m.callsTo('HEAD', '/downloads/smsgate.apk')[0]).toMatchObject({ redirect: 'manual' })
  })

  it('shows "coming soon" when the APK is missing', async () => {
    setup()
    renderApp('/docs')
    expect(await screen.findByText(/android app coming soon/i)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /download apk/i })).not.toBeInTheDocument()
  })

  it('shows "coming soon" when the check fails or returns the SPA page', async () => {
    setup([{ method: 'HEAD', path: '/downloads/smsgate.apk', networkError: true }])
    renderApp('/docs')
    expect(await screen.findByText(/android app coming soon/i)).toBeInTheDocument()
  })

  it('treats an HTML fallback (dev server) as not available', async () => {
    setup([{ method: 'HEAD', path: '/downloads/smsgate.apk', headers: { 'content-type': 'text/html' } }])
    renderApp('/docs')
    expect(await screen.findByText(/android app coming soon/i)).toBeInTheDocument()
  })
})
