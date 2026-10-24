import { describe, expect, it } from 'vitest'
import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { apiError, mockFetch } from '../test/mockFetch'
import { deferred, renderApp, verifiedMe } from '../test/renderApp'
import type { Device } from '../api/types'

const HOUR = 3_600_000

function devices(): Device[] {
  return [
    {
      id: 'd1',
      name: 'Pixel 8',
      online: true,
      sims: [
        { slot: 0, subscription_id: 1, carrier: 'Telia' },
        { slot: 1, subscription_id: 2, carrier: 'Bite', number: '+37060000001' },
      ],
      battery: 82,
      charging: true,
      last_seen_at: new Date(Date.now() - 10_000).toISOString(),
    },
    {
      id: 'd2',
      name: 'Old Galaxy',
      online: false,
      sims: [],
      battery: 12,
      charging: false,
      last_seen_at: new Date(Date.now() - 2 * HOUR).toISOString(),
    },
  ]
}

function card(name: string | RegExp) {
  return screen.getByRole('article', { name })
}

describe('Devices', () => {
  it('lists devices with online state, last seen, battery and SIMs', async () => {
    mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', json: { data: devices() } },
    ])
    renderApp('/devices')

    const online = await screen.findByRole('article', { name: 'Pixel 8' })
    expect(within(online).getByText('Online')).toBeInTheDocument()
    expect(within(online).getByRole('meter', { name: /battery/i })).toHaveAttribute('aria-valuenow', '82')
    expect(within(online).getByLabelText(/charging/i)).toBeInTheDocument()
    expect(within(online).getByText('Telia').closest('li')).toHaveTextContent('SIM 1')
    expect(within(online).getByText('Bite').closest('li')).toHaveTextContent('SIM 2')

    const offline = card('Old Galaxy')
    expect(within(offline).getByText(/last seen 2 hours ago/i)).toBeInTheDocument()
    expect(within(offline).queryByText('Online')).not.toBeInTheDocument()
    expect(within(offline).getByText(/no sims reported/i)).toBeInTheDocument()
  })

  it('shows an empty state with a pair CTA when there are no devices', async () => {
    mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', json: { data: [] } },
    ])
    renderApp('/devices')
    expect(await screen.findByRole('button', { name: /pair your first phone/i })).toBeInTheDocument()
  })

  it('shows an error state and Retry refetches', async () => {
    const user = userEvent.setup()
    const m = mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', ...apiError(500, 'internal'), once: true },
      { path: '/app/devices', json: { data: devices() } },
    ])
    renderApp('/devices')

    await user.click(await screen.findByRole('button', { name: /retry/i }))
    expect(await screen.findByRole('article', { name: 'Pixel 8' })).toBeInTheDocument()
    expect(m.callsTo('GET', '/app/devices')).toHaveLength(2)
  })

  it('renames optimistically, then PATCHes the new name', async () => {
    const user = userEvent.setup()
    const gate = deferred()
    const m = mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', json: { data: devices() }, once: true },
      { path: '/app/devices', json: { data: devices().map((d) => (d.id === 'd1' ? { ...d, name: 'Front desk' } : d)) } },
      {
        method: 'PATCH',
        path: '/app/devices/d1',
        json: { ...devices()[0], name: 'Front desk' },
        until: gate.promise,
      },
    ])
    renderApp('/devices')

    await user.click(await screen.findByRole('button', { name: /rename pixel 8/i }))
    const input = screen.getByRole('textbox', { name: /device name/i })
    await user.clear(input)
    await user.type(input, 'Front desk{Enter}')

    // Shown before the server answers.
    expect(card('Front desk')).toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: /device name/i })).not.toBeInTheDocument()
    await waitFor(() => expect(m.callsTo('PATCH', '/app/devices/d1')).toHaveLength(1))
    expect(m.callsTo('PATCH', '/app/devices/d1')[0]!.body).toEqual({ name: 'Front desk' })
    gate.resolve()
    expect(await screen.findByRole('article', { name: 'Front desk' })).toBeInTheDocument()
  })

  it('Esc cancels a rename without a request', async () => {
    const user = userEvent.setup()
    const m = mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', json: { data: devices() } },
    ])
    renderApp('/devices')

    await user.click(await screen.findByRole('button', { name: /rename pixel 8/i }))
    await user.type(screen.getByRole('textbox', { name: /device name/i }), ' XL{Escape}')
    expect(card('Pixel 8')).toBeInTheDocument()
    expect(m.callsTo('PATCH', /\/app\/devices\//)).toHaveLength(0)
  })

  it('revokes after confirmation: DELETE, card removed', async () => {
    const user = userEvent.setup()
    const m = mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', json: { data: devices() }, once: true },
      { path: '/app/devices', json: { data: devices().slice(1) } },
      { method: 'DELETE', path: '/app/devices/d1', status: 204 },
    ])
    renderApp('/devices')

    await user.click(await screen.findByRole('button', { name: /revoke pixel 8/i }))
    const dialog = await screen.findByRole('dialog', { name: /revoke pixel 8/i })
    await user.click(within(dialog).getByRole('button', { name: /revoke device/i }))

    await waitFor(() => expect(screen.queryByRole('article', { name: 'Pixel 8' })).not.toBeInTheDocument())
    expect(m.callsTo('DELETE', '/app/devices/d1')).toHaveLength(1)
    expect(card('Old Galaxy')).toBeInTheDocument()
  })

  it('a failed revoke rolls the card back and shows an error toast', async () => {
    const user = userEvent.setup()
    const gate = deferred()
    mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', json: { data: devices() } },
      { method: 'DELETE', path: '/app/devices/d1', ...apiError(500, 'internal'), until: gate.promise },
    ])
    renderApp('/devices')

    await user.click(await screen.findByRole('button', { name: /revoke pixel 8/i }))
    const dialog = await screen.findByRole('dialog', { name: /revoke pixel 8/i })
    await user.click(within(dialog).getByRole('button', { name: /revoke device/i }))

    // Optimistically gone, then back after the failure.
    await waitFor(() => expect(screen.queryByRole('article', { name: 'Pixel 8' })).not.toBeInTheDocument())
    gate.resolve()
    expect(await screen.findByRole('article', { name: 'Pixel 8' })).toBeInTheDocument()
    const errors = screen.getByRole('region', { name: 'Errors' })
    expect(await within(errors).findByText(/couldn.t revoke pixel 8/i)).toBeInTheDocument()
  })

  it('keeps "Pair a phone" disabled until the devices list has loaded', async () => {
    const gate = deferred()
    mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', json: { data: devices() }, until: gate.promise },
    ])
    renderApp('/devices')
    const pair = await screen.findByRole('button', { name: /^pair a phone$/i })
    expect(pair).toBeDisabled()
    gate.resolve()
    await waitFor(() => expect(pair).toBeEnabled())
  })

  it('returns focus to the rename button after Enter or Esc', async () => {
    const user = userEvent.setup()
    mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', json: { data: devices() }, once: true },
      { path: '/app/devices', json: { data: devices().map((d) => (d.id === 'd1' ? { ...d, name: 'Front desk' } : d)) } },
      { method: 'PATCH', path: '/app/devices/d1', json: { ...devices()[0], name: 'Front desk' } },
    ])
    renderApp('/devices')

    await user.click(await screen.findByRole('button', { name: /rename pixel 8/i }))
    await user.keyboard('{Escape}')
    expect(screen.getByRole('button', { name: /rename pixel 8/i })).toHaveFocus()

    await user.keyboard('{Enter}')
    const input = screen.getByRole('textbox', { name: /device name/i })
    await user.clear(input)
    await user.type(input, 'Front desk{Enter}')
    expect(screen.getByRole('button', { name: /rename front desk/i })).toHaveFocus()
  })

  it('refetches the list after a rename settles', async () => {
    const user = userEvent.setup()
    const m = mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', json: { data: devices() } },
      { method: 'PATCH', path: '/app/devices/d1', json: { ...devices()[0], name: 'Front desk' } },
    ])
    renderApp('/devices')
    await user.click(await screen.findByRole('button', { name: /rename pixel 8/i }))
    const input = screen.getByRole('textbox', { name: /device name/i })
    await user.clear(input)
    await user.type(input, 'Front desk{Enter}')
    await waitFor(() => expect(m.callsTo('PATCH', '/app/devices/d1')).toHaveLength(1))
    await waitFor(() => expect(m.callsTo('GET', '/app/devices')).toHaveLength(2))
  })

  it('shows a last_seen in the future (clock skew) as "just now"', async () => {
    const [, offline] = devices()
    mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', json: { data: [{ ...offline!, last_seen_at: new Date(Date.now() + 2 * 60_000).toISOString() }] } },
    ])
    renderApp('/devices')
    const offlineCard = await screen.findByRole('article', { name: 'Old Galaxy' })
    expect(within(offlineCard).getByText(/last seen just now/i)).toBeInTheDocument()
  })
})

describe('Devices: resilience and layout', () => {
  it('keeps the list when a background refetch fails', async () => {
    const m = mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', json: { data: devices() } },
    ])
    const { queryClient } = renderApp('/devices')
    await screen.findByRole('article', { name: 'Pixel 8' })

    m.use({ path: '/app/devices', ...apiError(500, 'internal') })
    await act(() => queryClient.refetchQueries({ queryKey: ['devices'] }))
    expect(queryClient.getQueryState(['devices'])?.status).toBe('error')
    // Let the query observers' batched notification reach React.
    await act(() => new Promise((r) => setTimeout(r, 20)))
    expect(screen.getByRole('article', { name: 'Pixel 8' })).toBeInTheDocument()
    expect(screen.queryByText(/couldn’t load your devices/i)).not.toBeInTheDocument()
  })

  // Regression (320px phones): an untruncated device ID widened the card and
  // the page past the viewport. Every box between the grid and the ID must
  // be allowed to shrink, and the ID must truncate.
  it('lets the device ID truncate inside a shrinkable card', async () => {
    mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', json: { data: devices() } },
    ])
    renderApp('/devices')
    const article = await screen.findByRole('article', { name: 'Pixel 8' })
    const id = within(article).getByTestId('device-id')
    expect(id).toHaveClass('truncate', 'min-w-0')

    const list = article.closest('ul')!
    expect(list).toHaveClass('grid-cols-1')
    for (let el = id.parentElement; el && el !== list; el = el.parentElement) {
      expect(el, `<${el.tagName.toLowerCase()} class="${el.className}">`).toHaveClass('min-w-0')
    }
  })
})
