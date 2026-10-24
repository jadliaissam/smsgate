import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { apiError, mockFetch, type MockRoute } from '../test/mockFetch'
import { deferred, locationOf, renderApp, unverifiedMe, verifiedMe } from '../test/renderApp'
import type { ApiKey, Device, Message, Stats, StatusCounts } from '../api/types'

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
const activeKey: ApiKey = { ...revokedKey, id: 'k2', name: 'prod', revoked_at: null }

const zero: StatusCounts = { queued: 0, dispatched: 0, sent: 0, delivered: 0, failed: 0, expired: 0 }

function stats(month: Partial<StatusCounts> = {}, last24h: Partial<StatusCounts> = {}, quota = 100): Stats {
  const m = { ...zero, ...month }
  const d = { ...zero, ...last24h }
  const sum = (c: StatusCounts) => Object.values(c).reduce((a, b) => a + b, 0)
  return {
    plan: { id: 'free', name: 'Free', monthly_message_quota: quota, max_devices: 1 },
    month: { start: '2026-10-01T00:00:00Z', total: sum(m), by_status: m },
    last_24h: { since: '2026-10-23T12:00:00Z', total: sum(d), by_status: d },
  }
}

// The backend example: 12 this month, 3 sent / 8 delivered / 1 failed.
const sample = stats({ sent: 3, delivered: 8, failed: 1 }, { sent: 3, delivered: 8, failed: 1 })
const empty = stats()

function msg(id: string, over: Partial<Message> = {}): Message {
  return {
    id,
    device_id: device.id,
    sim_slot: null,
    to: '+37061234567',
    status: 'delivered',
    client_ref: null,
    error_code: null,
    error_message: null,
    created_at: '2026-10-24T07:00:00Z',
    expires_at: '2026-10-25T07:00:00Z',
    dispatched_at: '2026-10-24T07:00:01Z',
    sent_at: '2026-10-24T07:00:02Z',
    delivered_at: '2026-10-24T07:00:05Z',
    failed_at: null,
    ...over,
  }
}

const RECENT = '/app/messages?limit=5'

/** me, devices, keys, stats and recent messages; `extra` routes win. */
function routes(
  { me = verifiedMe, devices = [device], keys = [revokedKey], s = sample, recent = [] as Message[] } = {},
  ...extra: MockRoute[]
): MockRoute[] {
  return [
    ...extra,
    { path: '/app/me', json: me },
    { path: '/app/devices', json: { data: devices } },
    { path: '/app/api-keys', json: { data: keys } },
    { path: '/app/stats', json: s },
    { path: RECENT, json: { data: recent } },
  ]
}

function step(name: RegExp) {
  return screen.getByRole('listitem', { name })
}

async function card(name: RegExp) {
  return screen.findByRole('region', { name })
}

// [Review Focus 4] Skeleton while loading, error state with Retry.
describe('Overview', () => {
  it('shows skeletons first, then the checklist computed from me, devices and keys', async () => {
    const gate = deferred()
    mockFetch([
      { path: '/app/me', json: verifiedMe },
      { path: '/app/devices', json: { data: [device] }, until: gate.promise },
      { path: '/app/api-keys', json: { data: [revokedKey] }, until: gate.promise },
      { path: '/app/stats', json: empty, until: gate.promise },
      { path: RECENT, json: { data: [] }, until: gate.promise },
    ])
    renderApp('/')

    expect(await screen.findByTestId('checklist-skeleton')).toBeInTheDocument()
    expect(screen.getByTestId('stats-skeleton')).toBeInTheDocument()
    expect(screen.getByTestId('recent-skeleton')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(/ada/i)
    gate.resolve()

    expect(await screen.findByText(/2 of 4 done/i)).toBeInTheDocument()
    expect(screen.queryByTestId('checklist-skeleton')).not.toBeInTheDocument()
    expect(screen.queryByTestId('stats-skeleton')).not.toBeInTheDocument()
    expect(screen.queryByTestId('recent-skeleton')).not.toBeInTheDocument()
    expect(step(/verify your email/i)).toHaveAttribute('data-done', 'true')
    expect(step(/pair a phone/i)).toHaveAttribute('data-done', 'true')
    // A revoked key does not count.
    expect(step(/create an api key/i)).toHaveAttribute('data-done', 'false')
    expect(step(/send your first sms/i)).toHaveAttribute('data-done', 'false')
    expect(within(step(/create an api key/i)).getByRole('link', { name: /create a key/i })).toHaveAttribute(
      'href',
      '/api-keys',
    )
  })

  it('unverified with nothing set up: 0 of 4, links to each page, curl uses the origin', async () => {
    mockFetch(routes({ me: unverifiedMe, devices: [], keys: [], s: empty }))
    renderApp('/')

    expect(await screen.findByText(/0 of 4 done/i)).toBeInTheDocument()
    expect(step(/verify your email/i)).toHaveAttribute('data-done', 'false')
    expect(within(step(/pair a phone/i)).getByRole('link', { name: /pair a phone/i })).toHaveAttribute(
      'href',
      '/devices',
    )
    const send = step(/send your first sms/i)
    expect(within(send).getByText(new RegExp(`${window.location.origin}/v1/messages`))).toBeInTheDocument()
    expect(within(send).getByRole('button', { name: /copy/i })).toBeInTheDocument()
    expect(within(send).getByRole('link', { name: /send a test/i })).toHaveAttribute('href', '/messages?send=true')
  })

  it('fills the curl snippet with the first device id when one exists', async () => {
    mockFetch(routes({ keys: [], s: empty }))
    renderApp('/')
    await screen.findByText(/2 of 4 done/i)
    expect(within(step(/send your first sms/i)).getByText(new RegExp(device.id))).toBeInTheDocument()
  })

  it('shows an error state and Retry refetches', async () => {
    const user = userEvent.setup()
    const m = mockFetch(
      routes({ devices: [], keys: [], s: empty }, { path: '/app/devices', ...apiError(500, 'internal'), once: true }),
    )
    renderApp('/')

    const alert = (await screen.findByText(/couldn.t load your setup/i)).closest('[role="alert"]') as HTMLElement
    await user.click(within(alert).getByRole('button', { name: /retry/i }))
    expect(await screen.findByText(/1 of 4 done/i)).toBeInTheDocument()
    await waitFor(() => expect(m.callsTo('GET', '/app/devices')).toHaveLength(2))
  })

  it('usage this month: total / quota with a progress bar and the plan badge', async () => {
    mockFetch(routes())
    renderApp('/')

    const usage = await card(/usage this month/i)
    expect(usage).toHaveTextContent('12 / 100')
    expect(within(usage).getByText('Free')).toBeInTheDocument()
    const bar = within(usage).getByRole('progressbar', { name: /quota/i })
    expect(bar).toHaveAttribute('aria-valuenow', '12')
    expect(bar).toHaveAttribute('aria-valuemax', '100')
    expect(bar).toHaveAttribute('data-tone', 'brand')
    expect(usage).toHaveTextContent(/88 left/)
  })

  it.each([
    [79, 'brand'],
    [80, 'warning'],
    [100, 'danger'],
    [130, 'danger'],
  ])('usage at %i of 100 has the %s tone', async (sent, tone) => {
    mockFetch(routes({ s: stats({ sent }) }))
    renderApp('/')
    const usage = await card(/usage this month/i)
    expect(usage).toHaveTextContent(`${sent} / 100`)
    expect(within(usage).getByRole('progressbar')).toHaveAttribute('data-tone', tone)
  })

  it('no quota: no progress bar and no division by zero', async () => {
    mockFetch(routes({ s: stats({ sent: 4 }, {}, 0) }))
    renderApp('/')
    const usage = await card(/usage this month/i)
    expect(usage).toHaveTextContent(/^.*4.*no monthly quota/i)
    expect(within(usage).queryByRole('progressbar')).not.toBeInTheDocument()
    expect(usage).not.toHaveTextContent(/NaN|Infinity/)
  })

  it('devices: online / total against the plan limit', async () => {
    mockFetch(routes({ devices: [device, { ...device, id: 'd2', online: false }] }))
    renderApp('/')
    const devices = await card(/^devices$/i)
    expect(devices).toHaveTextContent('1 / 2')
    expect(devices).toHaveTextContent(/2 of 1 slot used on the Free plan · limit reached/)
  })

  it('delivery rate is (sent + delivered) / (sent + delivered + failed + expired)', async () => {
    mockFetch(routes())
    renderApp('/')
    const rate = await card(/delivery rate/i)
    // 11 / 12, rounded down to 0.1 %.
    expect(rate).toHaveTextContent('91.6%')
    expect(rate).toHaveTextContent(/11 of 12 finished/)

    const breakdown = await card(/last 24 h by status/i)
    expect(within(breakdown).getByRole('img')).toHaveAccessibleName(/3 sent, 8 delivered, 1 failed/)
    const legend = within(breakdown).getByRole('list', { name: /status counts/i })
    expect(within(legend).getAllByRole('listitem')).toHaveLength(6)
    expect(within(legend).getByText('Delivered').parentElement).toHaveTextContent('Delivered8')
  })

  it('delivery rate shows "—" when nothing has finished', async () => {
    mockFetch(routes({ s: stats({ queued: 2 }, { queued: 2, dispatched: 1 }) }))
    renderApp('/')
    const rate = await card(/delivery rate/i)
    expect(within(rate).getByText('—')).toBeInTheDocument()
    expect(rate).toHaveTextContent(/no finished messages yet · 3 in flight/i)
  })

  it('stats error → Retry refetches', async () => {
    const user = userEvent.setup()
    const m = mockFetch(routes({}, { path: '/app/stats', ...apiError(500, 'internal'), once: true }))
    renderApp('/')

    const alert = (await screen.findByText(/couldn.t load your usage/i)).closest('[role="alert"]') as HTMLElement
    await user.click(within(alert).getByRole('button', { name: /retry/i }))
    expect(await card(/usage this month/i)).toHaveTextContent('12 / 100')
    expect(m.callsTo('GET', '/app/stats')).toHaveLength(2)
  })

  it('recent messages: the 5 newest, View all links to /messages, a row opens its drawer', async () => {
    const user = userEvent.setup()
    const recent = ['m1', 'm2', 'm3', 'm4', 'm5'].map((id) => msg(id))
    const m = mockFetch(
      routes({ recent }, { path: '/app/messages?limit=50', json: { data: recent } }),
    )
    const { router } = renderApp('/')

    const region = await card(/recent messages/i)
    const list = await within(region).findByRole('list', { name: /recent messages/i })
    expect(within(list).getAllByRole('listitem')).toHaveLength(5)
    expect(m.callsTo('GET', RECENT)).toHaveLength(1)
    expect(within(region).getByRole('link', { name: /view all/i })).toHaveAttribute('href', '/messages')

    await user.click(within(list).getAllByRole('button')[1]!)
    await waitFor(() => expect(locationOf(router)).toBe('/messages?message=m2'))
    const drawer = await screen.findByRole('dialog')
    expect(await within(drawer).findByText(/timeline/i)).toBeInTheDocument()
  })

  it('recent messages error → Retry refetches', async () => {
    const user = userEvent.setup()
    const m = mockFetch(routes({ recent: [msg('m1')] }, { path: RECENT, ...apiError(500, 'internal'), once: true }))
    renderApp('/')

    const alert = (await screen.findByText(/couldn.t load recent messages/i)).closest('[role="alert"]') as HTMLElement
    await user.click(within(alert).getByRole('button', { name: /retry/i }))
    expect(await screen.findByRole('list', { name: /recent messages/i })).toBeInTheDocument()
    expect(m.callsTo('GET', RECENT)).toHaveLength(2)
  })

  it('no messages yet: the empty state opens the send-test flow on /messages', async () => {
    const user = userEvent.setup()
    mockFetch(routes({ s: empty }, { path: '/app/messages?limit=50', json: { data: [] } }))
    const { router } = renderApp('/')

    const region = await card(/recent messages/i)
    const link = await within(region).findByRole('link', { name: /send a test message/i })
    expect(link).toHaveAttribute('href', '/messages?send=true')
    await user.click(link)
    expect(await screen.findByRole('dialog', { name: /send a test message/i })).toBeInTheDocument()
    expect(locationOf(router)).toBe('/messages?send=true')
  })

  it('the send step completes when this month has messages; a complete checklist folds away', async () => {
    const user = userEvent.setup()
    mockFetch(routes({ keys: [activeKey] }))
    renderApp('/')

    expect(await screen.findByText(/4 of 4 done/i)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /setup complete/i })).toBeInTheDocument()
    expect(screen.queryByRole('listitem', { name: /send your first sms/i })).not.toBeInTheDocument()

    const toggle = screen.getByRole('button', { name: /show steps/i })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    const send = step(/send your first sms/i)
    expect(send).toHaveAttribute('data-done', 'true')
    expect(within(send).getByRole('link', { name: /view messages/i })).toHaveAttribute('href', '/messages')
  })

  it('the send step also completes when any message exists', async () => {
    mockFetch(routes({ keys: [], s: empty, recent: [msg('m1')] }))
    renderApp('/')
    expect(await screen.findByText(/3 of 4 done/i)).toBeInTheDocument()
    expect(step(/send your first sms/i)).toHaveAttribute('data-done', 'true')
  })
})
