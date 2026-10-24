import { useId, useMemo, useState, type ReactNode } from 'react'
import { Link, useNavigate } from '@tanstack/react-router'
import { isApiError } from '../api/client'
import { errorCopy } from '../api/errors'
import { useApiKeys, useDevices, useMessages, useStats } from '../api/queries'
import type { Device, Message, Stats, StatusCounts } from '../api/types'
import { ArrowRightIcon, BoltIcon, ChevronDownIcon, KeyIcon, MailIcon, MessageIcon, PhoneIcon, SendIcon } from '../layout/icons'
import { ResendButton } from '../layout/ResendButton'
import { useSessionUser } from '../layout/session'
import { Badge, type BadgeTone } from '../ui/Badge'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'
import { CopyButton } from '../ui/CopyButton'
import { buttonStyles, cx, focusRing } from '../ui/cx'
import { EmptyState } from '../ui/EmptyState'
import { AlertIcon, CheckCircleIcon, CheckIcon } from '../ui/icons'
import { messageStatuses, statusFill, statusLabel } from '../ui/messageStatus'
import { Skeleton } from '../ui/Skeleton'
import { useNow } from '../ui/time'
import { MessageListHeader, MessageRow } from './MessageRow'

/** How many messages the Overview lists. */
const RECENT_LIMIT = 5
/** Setup steps: verify, pair, key, first message. */
const SETUP_STEPS = 4

function greeting(hour: number): string {
  if (hour < 5) return 'Good evening'
  if (hour < 12) return 'Good morning'
  if (hour < 18) return 'Good afternoon'
  return 'Good evening'
}

/** "ada.lovelace@example.com" → "Ada". */
function firstName(email: string): string {
  const local = email.split('@')[0] ?? ''
  const word = local.split(/[._+-]/)[0] || local
  return word.charAt(0).toUpperCase() + word.slice(1)
}

function curlSnippet(origin: string, deviceId: string | undefined): string {
  const body = JSON.stringify({ device_id: deviceId ?? '<device-id>', to: '+37060000000', body: 'Hello from SMSGate' })
  return [
    `curl -X POST ${origin}/v1/messages \\`,
    `  -H "Authorization: Bearer $SMSGATE_API_KEY" \\`,
    `  -H "Content-Type: application/json" \\`,
    `  -d '${body}'`,
  ].join('\n')
}

const count = new Intl.NumberFormat()
const percent = new Intl.NumberFormat(undefined, { style: 'percent', maximumFractionDigits: 1 })

/**
 * Share of finished messages that made it out: (sent + delivered) /
 * (sent + delivered + failed + expired). Null when nothing has finished.
 */
function deliveryRate(by: StatusCounts): { rate: number | null; ok: number; finished: number } {
  const ok = by.sent + by.delivered
  const finished = ok + by.failed + by.expired
  return { rate: finished > 0 ? ok / finished : null, ok, finished }
}

/** Rounded down to 0.1 %, so a single failure never shows as 100 %. */
function formatRate(rate: number): string {
  return percent.format(Math.floor(rate * 1000) / 1000)
}

type UsageTone = 'brand' | 'warning' | 'danger'

function usageTone(pct: number): UsageTone {
  if (pct >= 100) return 'danger'
  if (pct >= 80) return 'warning'
  return 'brand'
}

/** First day of the month after `start` (the quota window's reset), in UTC. */
function resetDate(start: string): string {
  const d = new Date(start)
  const next = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 1))
  return next.toLocaleDateString(undefined, { month: 'short', day: 'numeric', timeZone: 'UTC' })
}

export function Overview() {
  const me = useSessionUser()
  const devices = useDevices()
  const keys = useApiKeys()
  const stats = useStats()
  const recent = useMessages({}, { limit: RECENT_LIMIT })
  const recentRows = useMemo(() => recent.data?.pages[0]?.data.slice(0, RECENT_LIMIT), [recent.data])

  const setup =
    devices.data && keys.data && !(stats.isPending && recent.isPending)
      ? {
          verified: me.email_verified,
          paired: devices.data.length > 0,
          hasKey: keys.data.some((k) => k.revoked_at === null),
          sent: (stats.data?.month.total ?? 0) > 0 || (recentRows?.length ?? 0) > 0,
        }
      : undefined
  const done = setup ? [setup.verified, setup.paired, setup.hasKey, setup.sent].filter(Boolean).length : 0
  const complete = done === SETUP_STEPS

  const checklist = <Checklist setup={setup} done={done} devices={devices} keys={keys} />
  const recentCard = <RecentMessages query={recent} rows={recentRows} devices={devices.data} />

  return (
    <>
      <section className="relative mb-6 overflow-hidden rounded-2xl border border-border bg-surface p-6 shadow-card sm:p-8">
        <div
          aria-hidden="true"
          className="pointer-events-none absolute -top-24 -right-16 size-80 rounded-full bg-radial from-brand-400/25 to-transparent to-70% dark:from-brand-500/20"
        />
        <div
          aria-hidden="true"
          className="pointer-events-none absolute -bottom-32 left-1/3 size-72 rounded-full bg-radial from-brand-200/40 to-transparent to-70% dark:from-brand-800/30"
        />
        <p className="relative text-sm font-medium text-brand-600 dark:text-brand-400">
          {complete ? 'Overview' : 'Get started'}
        </p>
        <h1 className="relative mt-1 text-2xl font-semibold tracking-tight text-balance text-fg sm:text-3xl">
          {greeting(new Date().getHours())}, {firstName(me.email)}
        </h1>
        <p className="relative mt-2 max-w-xl text-sm text-pretty text-fg-muted sm:text-[15px]">
          {complete
            ? 'Your gateway is set up. Here’s how it’s doing.'
            : 'A few steps and your phone becomes an SMS gateway. Here’s where you are.'}
        </p>
      </section>

      <StatCards stats={stats} devices={devices} />

      {complete ? (
        <>
          <div className="mb-6">{recentCard}</div>
          {checklist}
        </>
      ) : (
        <>
          <div className="mb-6">{checklist}</div>
          {recentCard}
        </>
      )}
    </>
  )
}

// ---- stat cards ------------------------------------------------------------

type StatsQuery = ReturnType<typeof useStats>
type DevicesQuery = ReturnType<typeof useDevices>

function LoadError({ title, error, retrying, onRetry, className }: {
  title: string
  error: unknown
  retrying: boolean
  onRetry: () => void
  className?: string
}) {
  return (
    <div role="alert" className={cx('flex flex-col items-center text-center', className)}>
      <span className="mb-3 flex size-10 items-center justify-center rounded-2xl bg-danger-50 text-lg text-danger-600 dark:bg-danger-500/10 dark:text-danger-400">
        <AlertIcon />
      </span>
      <h3 className="text-sm font-semibold tracking-tight text-fg">{title}</h3>
      <p className="mt-1 max-w-sm text-[13px] text-pretty text-fg-muted">
        {errorCopy(isApiError(error) ? error.code : 'default')}
      </p>
      <Button variant="secondary" size="sm" className="mt-4" loading={retrying} onClick={onRetry}>
        Retry
      </Button>
    </div>
  )
}

function StatCards({ stats, devices }: { stats: StatsQuery; devices: DevicesQuery }) {
  if (stats.error && !stats.data) {
    return (
      <Card className="mb-6">
        <LoadError
          title="Couldn’t load your usage"
          error={stats.error}
          retrying={stats.isFetching}
          onRetry={() => void stats.refetch()}
          className="py-6"
        />
      </Card>
    )
  }
  if (!stats.data) {
    return (
      <div
        data-testid="stats-skeleton"
        aria-busy="true"
        aria-label="Loading your usage"
        className="mb-6 grid gap-4 sm:grid-cols-2 xl:grid-cols-4"
      >
        {[0, 1, 2, 3].map((i) => (
          <Card key={i} className="flex flex-col gap-4" padding="sm">
            <div className="flex items-center justify-between">
              <Skeleton className="h-3.5 w-28" />
              <Skeleton circle className="size-8" />
            </div>
            <Skeleton className="h-8 w-24" />
            <Skeleton className="h-2 w-full" />
            <Skeleton className="h-3 w-36" />
          </Card>
        ))}
      </div>
    )
  }
  const s = stats.data
  return (
    <div className="mb-6 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <UsageCard stats={s} />
      <DevicesCard devices={devices} maxDevices={s.plan.max_devices} planName={s.plan.name} />
      <DeliveryCard by={s.last_24h.by_status} />
      <BreakdownCard by={s.last_24h.by_status} total={s.last_24h.total} />
    </div>
  )
}

const iconTones: Record<BadgeTone, string> = {
  neutral: 'bg-surface-sunken text-fg-muted',
  brand: 'bg-brand-50 text-brand-600 dark:bg-brand-500/12 dark:text-brand-300',
  success: 'bg-success-50 text-success-600 dark:bg-success-500/12 dark:text-success-300',
  warning: 'bg-warning-50 text-warning-700 dark:bg-warning-500/12 dark:text-warning-300',
  danger: 'bg-danger-50 text-danger-600 dark:bg-danger-500/12 dark:text-danger-300',
}

interface StatCardProps {
  title: string
  icon: ReactNode
  iconTone?: BadgeTone
  badge?: ReactNode
  children: ReactNode
  footer?: ReactNode
}

/** One stat: a small heading, a big value, and a footnote. A labelled region. */
function StatCard({ title, icon, iconTone = 'brand', badge, children, footer }: StatCardProps) {
  const id = useId()
  return (
    <Card
      role="region"
      aria-labelledby={id}
      padding="none"
      className="flex min-w-0 flex-col gap-3 p-5 animate-fade-in motion-reduce:animate-none"
    >
      <div className="flex items-start justify-between gap-3">
        <div className="flex min-w-0 items-center gap-2.5">
          <span
            aria-hidden="true"
            className={cx('flex size-8 shrink-0 items-center justify-center rounded-xl text-base inset-ring inset-ring-border/60', iconTones[iconTone])}
          >
            {icon}
          </span>
          <h2 id={id} className="truncate text-[13px] font-medium text-fg-muted">
            {title}
          </h2>
        </div>
        {badge}
      </div>
      {children}
      {footer && <p className="mt-auto text-xs text-pretty text-fg-muted">{footer}</p>}
    </Card>
  )
}

function BigValue({ children, className }: { children: ReactNode; className?: string }) {
  return <p className={cx('text-3xl font-semibold tracking-tight text-fg tabular-nums', className)}>{children}</p>
}

const usageFill: Record<UsageTone, string> = {
  brand: 'bg-linear-to-r from-brand-400 to-brand-600',
  warning: 'bg-linear-to-r from-warning-400 to-warning-500',
  danger: 'bg-linear-to-r from-danger-400 to-danger-600',
}

function UsageCard({ stats }: { stats: Stats }) {
  const { total } = stats.month
  const quota = stats.plan.monthly_message_quota
  const hasQuota = Number.isFinite(quota) && quota > 0
  const pct = hasQuota ? (total / quota) * 100 : 0
  const tone = usageTone(pct)
  const left = Math.max(0, quota - total)
  const resets = resetDate(stats.month.start)

  return (
    <StatCard
      title="Usage this month"
      icon={<BoltIcon />}
      iconTone={hasQuota && tone !== 'brand' ? tone : 'brand'}
      badge={<Badge tone="brand">{stats.plan.name}</Badge>}
      footer={
        !hasQuota ? (
          `No monthly quota on this plan. Resets ${resets}.`
        ) : tone === 'danger' ? (
          <span className="font-medium text-danger-700 dark:text-danger-300">Quota reached · resets {resets}</span>
        ) : (
          <>
            <span className={cx(tone === 'warning' && 'font-medium text-warning-800 dark:text-warning-300')}>
              {count.format(left)} left
            </span>{' '}
            · resets {resets}
          </>
        )
      }
    >
      <BigValue>
        <span>{count.format(total)}</span>
        {hasQuota && <span className="text-lg font-medium text-fg-subtle"> / {count.format(quota)}</span>}
      </BigValue>
      {hasQuota && (
        <div
          role="progressbar"
          aria-label="Monthly quota used"
          aria-valuemin={0}
          aria-valuemax={quota}
          aria-valuenow={Math.min(total, quota)}
          aria-valuetext={`${count.format(total)} of ${count.format(quota)} messages (${Math.round(pct)}%)`}
          data-tone={tone}
          className="h-2 overflow-hidden rounded-full bg-surface-sunken inset-ring inset-ring-border/50"
        >
          <div
            className={cx(
              'h-full rounded-full transition-[width] duration-500 ease-snappy motion-reduce:transition-none',
              usageFill[tone],
            )}
            style={{ width: `${Math.min(pct, 100)}%` }}
          />
        </div>
      )}
    </StatCard>
  )
}

function DevicesCard({ devices, maxDevices, planName }: { devices: DevicesQuery; maxDevices: number; planName: string }) {
  if (!devices.data) {
    return (
      <StatCard title="Devices" icon={<PhoneIcon />}>
        {devices.error ? (
          <LoadError
            title="Couldn’t load devices"
            error={devices.error}
            retrying={devices.isFetching}
            onRetry={() => void devices.refetch()}
            className="py-1"
          />
        ) : (
          <div aria-busy="true" aria-label="Loading devices" className="space-y-3">
            <Skeleton className="h-8 w-20" />
            <Skeleton className="h-3 w-32" />
          </div>
        )}
      </StatCard>
    )
  }
  const total = devices.data.length
  const online = devices.data.filter((d) => d.online).length
  const full = maxDevices > 0 && total >= maxDevices
  const slots = maxDevices > 0 ? `${total} of ${maxDevices} ${maxDevices === 1 ? 'slot' : 'slots'} used` : `${total} paired`

  return (
    <StatCard
      title="Devices"
      icon={<PhoneIcon />}
      iconTone={online > 0 ? 'success' : 'brand'}
      badge={
        total > 0 && (
          <Badge tone={online > 0 ? 'success' : 'neutral'} dot pulse={online > 0}>
            {online > 0 ? 'Online' : 'Offline'}
          </Badge>
        )
      }
      footer={
        total === 0 ? (
          <Link to="/devices" className={cx('rounded font-medium text-brand-600 hover:text-brand-700 dark:text-brand-400', focusRing)}>
            Pair your first phone
          </Link>
        ) : (
          <>
            {slots} on the {planName} plan{full && ' · limit reached'}
          </>
        )
      }
    >
      <BigValue>
        <span>{count.format(online)}</span>
        <span className="text-lg font-medium text-fg-subtle"> / {count.format(total)}</span>
        <span className="ml-1.5 text-sm font-medium tracking-normal text-fg-muted">online</span>
      </BigValue>
    </StatCard>
  )
}

function DeliveryCard({ by }: { by: StatusCounts }) {
  const { rate, ok, finished } = deliveryRate(by)
  const inFlight = by.queued + by.dispatched
  return (
    <StatCard
      title="Delivery rate, last 24 h"
      icon={<CheckCircleIcon />}
      iconTone={rate === null ? 'brand' : rate >= 0.95 ? 'success' : rate >= 0.8 ? 'warning' : 'danger'}
      footer={
        <>
          {finished === 0
            ? 'No finished messages yet'
            : `${count.format(ok)} of ${count.format(finished)} finished made it out`}
          {inFlight > 0 && ` · ${count.format(inFlight)} in flight`}
        </>
      }
    >
      <BigValue>
        {rate === null ? (
          <>
            <span aria-hidden="true">—</span>
            <span className="sr-only">No data</span>
          </>
        ) : (
          formatRate(rate)
        )}
      </BigValue>
    </StatCard>
  )
}

function BreakdownCard({ by, total }: { by: StatusCounts; total: number }) {
  const parts = messageStatuses.filter((s) => by[s] > 0)
  const summary =
    total === 0
      ? 'No messages in the last 24 hours'
      : `Last 24 hours by status: ${parts.map((s) => `${count.format(by[s])} ${statusLabel[s].toLowerCase()}`).join(', ')}`
  return (
    <StatCard
      title="Last 24 h by status"
      icon={<MessageIcon />}
      badge={
        <span className="text-xs font-medium text-fg-muted tabular-nums">
          {count.format(total)} {total === 1 ? 'message' : 'messages'}
        </span>
      }
    >
      <div
        role="img"
        aria-label={summary}
        className="flex h-2.5 gap-0.5 overflow-hidden rounded-full bg-surface-sunken inset-ring inset-ring-border/50"
      >
        {parts.map((s) => (
          <div
            key={s}
            data-status={s}
            className={cx(
              'h-full first:rounded-l-full last:rounded-r-full transition-[width] duration-500 ease-snappy motion-reduce:transition-none',
              statusFill[s],
            )}
            style={{ width: `${(by[s] / total) * 100}%` }}
          />
        ))}
      </div>
      <ul aria-label="Status counts, last 24 hours" className="grid grid-cols-2 gap-x-4 gap-y-1.5 text-xs">
        {messageStatuses.map((s) => (
          <li key={s} className={cx('flex items-center gap-2', by[s] === 0 && 'opacity-55')}>
            <span aria-hidden="true" className={cx('size-2 shrink-0 rounded-full', statusFill[s])} />
            <span className="truncate text-fg-muted">{statusLabel[s]}</span>
            <span className="ml-auto font-medium text-fg tabular-nums">{count.format(by[s])}</span>
          </li>
        ))}
      </ul>
    </StatCard>
  )
}

// ---- recent messages -------------------------------------------------------

type RecentQuery = ReturnType<typeof useMessages>

function RecentMessages({ query, rows, devices }: { query: RecentQuery; rows: Message[] | undefined; devices: Device[] | undefined }) {
  const id = useId()
  const navigate = useNavigate()
  const now = useNow(30_000)
  const names = useMemo(() => new Map(devices?.map((d) => [d.id, d.name])), [devices])

  let body: ReactNode
  if (query.error && !rows) {
    body = (
      <LoadError
        title="Couldn’t load recent messages"
        error={query.error}
        retrying={query.isFetching}
        onRetry={() => void query.refetch()}
        className="px-6 py-10"
      />
    )
  } else if (!rows) {
    body = (
      <div data-testid="recent-skeleton" aria-busy="true" aria-label="Loading recent messages">
        {[0, 1, 2].map((i) => (
          <div key={i} className="flex items-center gap-4 border-b border-border px-5 py-3.5 last:border-b-0 md:px-6">
            <Skeleton className="h-6 w-24 rounded-full" />
            <Skeleton className="h-4 w-36" />
            <Skeleton className="ml-auto h-3.5 w-20" />
          </div>
        ))}
      </div>
    )
  } else if (rows.length === 0) {
    body = (
      <EmptyState
        className="py-12"
        icon={<SendIcon />}
        title="No messages yet"
        description="Send a test message from the dashboard to watch it travel from queued to delivered."
        action={
          <Link to="/messages" search={{ send: true }} className={cx(buttonStyles('primary', 'md'), 'group/link')}>
            Send a test message
            <ArrowRightIcon className="size-4 transition-transform duration-150 motion-safe:group-hover/link:translate-x-0.5 motion-reduce:transition-none" />
          </Link>
        }
      />
    )
  } else {
    body = (
      <>
        <MessageListHeader compact />
        <ul aria-label="Recent messages" className="divide-y divide-border">
          {rows.map((m) => (
            <MessageRow
              key={m.id}
              message={m}
              deviceName={names.get(m.device_id)}
              now={now}
              compact
              onSelect={(sel) => void navigate({ to: '/messages', search: { message: sel.id } })}
            />
          ))}
        </ul>
      </>
    )
  }

  return (
    <Card role="region" aria-labelledby={id} padding="none" className="overflow-hidden">
      <div className="flex items-center justify-between gap-4 border-b border-border px-5 py-4 md:px-6">
        <div className="min-w-0">
          <h2 id={id} className="text-[15px] font-semibold tracking-tight text-fg">
            Recent messages
          </h2>
          <p className="mt-0.5 text-sm text-fg-muted">The newest {RECENT_LIMIT}, updated live.</p>
        </div>
        <Link to="/messages" className={cx(buttonStyles('ghost', 'sm'), 'group/link shrink-0')}>
          View all
          <ArrowRightIcon className="size-3.5 transition-transform duration-150 motion-safe:group-hover/link:translate-x-0.5 motion-reduce:transition-none" />
        </Link>
      </div>
      {body}
    </Card>
  )
}

// ---- setup checklist -------------------------------------------------------

interface Setup {
  verified: boolean
  paired: boolean
  hasKey: boolean
  sent: boolean
}

interface ChecklistProps {
  /** Undefined while loading. */
  setup: Setup | undefined
  done: number
  devices: DevicesQuery
  keys: ReturnType<typeof useApiKeys>
}

function Checklist({ setup, done, devices, keys }: ChecklistProps) {
  const [expanded, setExpanded] = useState(false)
  const listId = useId()
  const error = devices.error ?? keys.error

  if (error) {
    const retrying = devices.isFetching || keys.isFetching
    return (
      <Card role="alert" className="flex flex-col items-center py-12 text-center">
        <span className="mb-4 flex size-11 items-center justify-center rounded-2xl bg-danger-50 text-xl text-danger-600 dark:bg-danger-500/10 dark:text-danger-400">
          <AlertIcon />
        </span>
        <h2 className="text-[15px] font-semibold tracking-tight text-fg">Couldn’t load your setup</h2>
        <p className="mt-1.5 max-w-sm text-sm text-pretty text-fg-muted">
          {errorCopy(isApiError(error) ? error.code : 'default')}
        </p>
        <Button
          variant="secondary"
          className="mt-6"
          loading={retrying}
          onClick={() => {
            if (devices.isError) void devices.refetch()
            if (keys.isError) void keys.refetch()
          }}
        >
          Retry
        </Button>
      </Card>
    )
  }

  if (!setup || !devices.data) return <ChecklistSkeleton />

  const { verified, paired, hasKey, sent } = setup
  const firstDevice = devices.data[0]
  const complete = done === SETUP_STEPS
  // Once everything is done the steps fold away (one click to reopen) so the
  // stats lead the page.
  const collapsed = complete && !expanded

  return (
    <Card padding="none" className="overflow-hidden">
      <div className={cx('flex items-center gap-4 p-5 sm:p-6', !collapsed && 'border-b border-border')}>
        <ProgressRing done={done} total={SETUP_STEPS} />
        <div className="min-w-0 flex-1">
          <h2 className="text-[15px] font-semibold tracking-tight text-fg">
            {complete ? 'Setup complete' : 'Set up your gateway'}
          </h2>
          <p className="mt-0.5 text-sm text-fg-muted">
            {done} of {SETUP_STEPS} done{complete && ' — you’re sending.'}
          </p>
        </div>
        {complete && (
          <Button
            variant="ghost"
            size="sm"
            aria-expanded={!collapsed}
            aria-controls={listId}
            onClick={() => setExpanded((v) => !v)}
          >
            {collapsed ? 'Show steps' : 'Hide steps'}
            <ChevronDownIcon
              aria-hidden="true"
              className={cx(
                'size-3.5 transition-transform duration-200 ease-out motion-reduce:transition-none',
                !collapsed && 'rotate-180',
              )}
            />
          </Button>
        )}
      </div>

      <ol id={listId} hidden={collapsed} className="divide-y divide-border">
        <Step
          index={1}
          done={verified}
          icon={<MailIcon />}
          title="Verify your email"
          description={
            verified ? 'Your email address is confirmed.' : 'Click the link we emailed you. API keys need a verified email.'
          }
          action={verified ? null : <ResendButton />}
        />
        <Step
          index={2}
          done={paired}
          icon={<PhoneIcon />}
          title="Pair a phone"
          description={
            paired
              ? `${devices.data.length} ${devices.data.length === 1 ? 'phone' : 'phones'} paired.`
              : 'Install the SMSGate app on an Android phone and scan the pairing QR code.'
          }
          action={<StepLink to="/devices" primary={!paired} label={paired ? 'View devices' : 'Pair a phone'} />}
        />
        <Step
          index={3}
          done={hasKey}
          icon={<KeyIcon />}
          title="Create an API key"
          description={hasKey ? 'Your servers can authenticate.' : 'Your servers use it to call the SMSGate API.'}
          action={<StepLink to="/api-keys" primary={!hasKey && paired} label={hasKey ? 'View keys' : 'Create a key'} />}
        />
        <SendStep deviceId={firstDevice?.id} done={sent} primary={paired && hasKey && !sent} />
      </ol>
    </Card>
  )
}

function ChecklistSkeleton() {
  return (
    <Card padding="none" data-testid="checklist-skeleton" aria-busy="true" aria-label="Loading your setup">
      <div className="flex items-center gap-4 border-b border-border p-5 sm:p-6">
        <Skeleton circle className="size-14" />
        <div className="flex-1 space-y-2">
          <Skeleton className="h-4 w-40" />
          <Skeleton className="h-3.5 w-24" />
        </div>
      </div>
      {[0, 1, 2, 3].map((i) => (
        <div key={i} className="flex items-center gap-4 border-b border-border p-5 last:border-b-0 sm:px-6">
          <Skeleton circle className="size-9" />
          <div className="flex-1 space-y-2">
            <Skeleton className="h-4 w-36" />
            <Skeleton className="h-3.5 w-64 max-w-full" />
          </div>
        </div>
      ))}
    </Card>
  )
}

/** Circular progress: the arc eases to `done / total`. */
function ProgressRing({ done, total }: { done: number; total: number }) {
  const r = 24
  const c = 2 * Math.PI * r
  const complete = done === total
  return (
    <div className="relative size-14 shrink-0" aria-hidden="true">
      <svg viewBox="0 0 56 56" className="size-full -rotate-90">
        <circle cx="28" cy="28" r={r} fill="none" strokeWidth="5" className="stroke-surface-sunken" />
        <circle
          cx="28"
          cy="28"
          r={r}
          fill="none"
          strokeWidth="5"
          strokeLinecap="round"
          strokeDasharray={c}
          strokeDashoffset={c * (1 - done / total)}
          className={cx(
            'transition-[stroke-dashoffset] duration-700 ease-snappy motion-reduce:transition-none',
            complete ? 'stroke-success-500' : 'stroke-brand-500',
          )}
        />
      </svg>
      <span className="absolute inset-0 flex items-center justify-center text-sm font-semibold text-fg tabular-nums">
        {done}/{total}
      </span>
    </div>
  )
}

interface StepProps {
  index: number
  done?: boolean
  icon: ReactNode
  title: string
  description: ReactNode
  action?: ReactNode
  children?: ReactNode
}

function Step({ index, done = false, icon, title, description, action, children }: StepProps) {
  const titleId = useId()
  return (
    <li
      aria-labelledby={titleId}
      data-done={String(done)}
      className="group flex gap-4 p-5 sm:px-6"
    >
      <StepMarker index={index} done={done} icon={icon} />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-3">
          <div className="min-w-0">
            <h3
              id={titleId}
              className={cx('text-sm font-semibold tracking-tight', done ? 'text-fg-muted line-through decoration-fg-subtle/50' : 'text-fg')}
            >
              {title}
            </h3>
            <p className="mt-0.5 text-sm text-fg-muted">{description}</p>
          </div>
          {action && <div className="shrink-0">{action}</div>}
        </div>
        {children}
      </div>
    </li>
  )
}

function StepMarker({ index, done, icon }: { index: number; done: boolean; icon: ReactNode }) {
  return (
    <span className="relative flex size-9 shrink-0 items-center justify-center">
      {done ? (
        <span className="flex size-9 items-center justify-center rounded-full bg-success-600 text-lg text-fg-on-success shadow-control animate-pop-in motion-reduce:animate-none">
          <CheckIcon strokeWidth={2.5} />
          <span className="sr-only">Done:</span>
        </span>
      ) : (
        <span className="flex size-9 items-center justify-center rounded-full border border-border bg-surface-raised text-[17px] text-fg-muted shadow-control">
          {icon}
          <span className="sr-only">Step {index}:</span>
        </span>
      )}
    </span>
  )
}

interface StepLinkProps {
  to: '/devices' | '/api-keys' | '/messages'
  label: string
  primary: boolean
  /** Open the send-test modal on arrival (only for /messages). */
  send?: boolean
}

function StepLink({ to, label, primary, send = false }: StepLinkProps) {
  return (
    <Link
      to={to}
      search={send ? { send: true } : undefined}
      className={cx(buttonStyles(primary ? 'primary' : 'secondary', 'sm'), 'group/link')}
    >
      {label}
      <ArrowRightIcon className="size-3.5 transition-transform duration-150 motion-safe:group-hover/link:translate-x-0.5 motion-reduce:transition-none" />
    </Link>
  )
}

function SendStep({ deviceId, done, primary }: { deviceId: string | undefined; done: boolean; primary: boolean }) {
  const snippet = curlSnippet(window.location.origin, deviceId)
  return (
    <Step
      index={4}
      done={done}
      icon={<SendIcon />}
      title="Send your first SMS"
      description={
        done
          ? 'Your first message is out. Follow every message in the log.'
          : 'One HTTP call with your API key, or send a test from the dashboard.'
      }
      action={
        done ? (
          <StepLink to="/messages" primary={false} label="View messages" />
        ) : (
          <StepLink to="/messages" send primary={primary} label="Send a test" />
        )
      }
    >
      {!done && (
        <div className="mt-4 overflow-hidden rounded-xl border border-border bg-surface-sunken">
          <div className="flex items-center justify-between border-b border-border py-1 pr-1.5 pl-4">
            <span className="font-mono text-xs text-fg-subtle">curl</span>
            <CopyButton value={snippet} label="Copy command" />
          </div>
          <pre className="overflow-x-auto p-4 text-[12.5px] leading-6 text-fg">
            <code className="font-mono">{snippet}</code>
          </pre>
        </div>
      )}
    </Step>
  )
}
