import type { ReactNode } from 'react'
import type { Message, MessageStatus } from '../api/types'
import { CopyButton } from '../ui/CopyButton'
import { cx } from '../ui/cx'
import { Drawer } from '../ui/Drawer'
import { AlertIcon, CheckIcon, InfoIcon, XIcon } from '../ui/icons'
import { StatusBadge } from '../ui/StatusBadge'
import { absoluteTime, sinceTime, useNow } from '../ui/time'
import type { DeviceName } from './deviceNames'

type StepState = 'done' | 'current' | 'pending' | 'failed' | 'expired'

interface Step {
  key: string
  label: string
  at: string | null
  state: StepState
  detail?: ReactNode
}

// Progress through the happy path; failed/expired end it early.
const rank: Partial<Record<MessageStatus, number>> = { queued: 0, dispatched: 1, sent: 2, delivered: 3 }

/** created → dispatched → sent → delivered, or ending in failed / expired. */
function timeline(m: Message, device: string): Step[] {
  const steps: Step[] = [
    { key: 'created', label: 'Created', at: m.created_at, state: 'done', detail: `Queued for ${device}.` },
  ]
  const dispatched: Step = {
    key: 'dispatched',
    label: 'Dispatched',
    at: m.dispatched_at,
    state: 'done',
    detail: 'The phone picked it up.',
  }
  const sent: Step = { key: 'sent', label: 'Sent', at: m.sent_at, state: 'done', detail: 'Handed to the carrier.' }

  if (m.status === 'failed') {
    if (m.dispatched_at) steps.push(dispatched)
    if (m.sent_at) steps.push(sent)
    steps.push({
      key: 'failed',
      label: 'Failed',
      at: m.failed_at,
      state: 'failed',
      detail: (
        <>
          {m.error_code && (
            <code className="rounded-md border border-danger-200 bg-danger-50 px-1.5 py-0.5 font-mono text-xs text-danger-800 dark:border-danger-500/25 dark:bg-danger-500/10 dark:text-danger-200">
              {m.error_code}
            </code>
          )}
          {m.error_message && <span className="mt-1 block">{m.error_message}</span>}
          {!m.error_code && !m.error_message && 'The phone could not send it.'}
        </>
      ),
    })
    return steps
  }
  if (m.status === 'expired') {
    if (m.dispatched_at) steps.push(dispatched)
    steps.push({
      key: 'expired',
      label: 'Expired',
      at: m.expires_at,
      state: 'expired',
      detail: 'The phone didn’t send it before its time to live ran out, so it never will.',
    })
    return steps
  }

  const r = rank[m.status] ?? 0
  const state = (n: number, at: string | null): StepState => (at || r >= n ? 'done' : r + 1 === n ? 'current' : 'pending')
  steps.push(
    {
      ...dispatched,
      state: state(1, m.dispatched_at),
      detail: m.dispatched_at || r >= 1 ? dispatched.detail : `Waiting for ${device}. Expires ${absoluteTime(m.expires_at)}.`,
    },
    { ...sent, state: state(2, m.sent_at), detail: m.sent_at || r >= 2 ? sent.detail : undefined },
    {
      key: 'delivered',
      label: 'Delivered',
      at: m.delivered_at,
      state: state(3, m.delivered_at),
      detail:
        m.status === 'delivered'
          ? 'The carrier confirmed delivery.'
          : m.status === 'sent'
            ? 'Waiting for the carrier’s delivery report. Some carriers never send one.'
            : undefined,
    },
  )
  return steps
}

const markers: Record<StepState, { className: string; icon: ReactNode }> = {
  done: {
    className: 'bg-brand-600 text-fg-on-brand shadow-control',
    icon: <CheckIcon strokeWidth={2.5} />,
  },
  current: {
    className: 'border-2 border-brand-500 bg-surface-raised text-brand-600 dark:text-brand-400',
    icon: <span className="size-1.5 rounded-full bg-brand-500 animate-ping-soft motion-reduce:animate-none" />,
  },
  pending: { className: 'border border-border bg-surface-raised text-fg-subtle', icon: null },
  failed: { className: 'bg-danger-600 text-fg-on-danger shadow-control', icon: <XIcon strokeWidth={2.5} /> },
  expired: {
    className: 'bg-warning-100 text-warning-700 inset-ring inset-ring-warning-600/25 dark:bg-warning-500/15 dark:text-warning-300',
    icon: <AlertIcon strokeWidth={2.25} />,
  },
}

function Timeline({ message, device }: { message: Message; device: string }) {
  const now = useNow(30_000)
  const steps = timeline(message, device)
  return (
    <ol aria-label="Timeline" className="relative">
      {steps.map((s, i) => (
        <li
          key={s.key}
          data-state={s.state}
          aria-current={s.state === 'current' ? 'step' : undefined}
          className="relative flex gap-3.5 pb-6 last:pb-0 animate-step-in motion-reduce:animate-none"
          style={{ animationDelay: `${i * 70}ms` }}
        >
          {i < steps.length - 1 && (
            <span
              aria-hidden="true"
              className={cx(
                'absolute top-7 bottom-1 left-[0.8125rem] w-px',
                steps[i + 1]!.state === 'pending' || steps[i + 1]!.state === 'current' ? 'bg-border' : 'bg-brand-300 dark:bg-brand-700',
              )}
            />
          )}
          <span
            aria-hidden="true"
            className={cx('relative flex size-6.5 shrink-0 items-center justify-center rounded-full text-[13px]', markers[s.state].className)}
          >
            {markers[s.state].icon}
          </span>
          <div className="min-w-0 flex-1 pt-0.5">
            <div className="flex flex-wrap items-baseline justify-between gap-x-3">
              <p className={cx('text-sm font-semibold tracking-tight', s.state === 'pending' ? 'text-fg-subtle' : 'text-fg')}>
                {s.label}
              </p>
              {s.at && s.state !== 'pending' && s.state !== 'current' ? (
                <time dateTime={s.at} title={sinceTime(s.at, now)} className="text-xs text-fg-muted tabular-nums">
                  {absoluteTime(s.at)}
                </time>
              ) : s.state === 'current' ? (
                <span className="text-xs font-medium text-brand-600 dark:text-brand-400">In progress</span>
              ) : null}
            </div>
            {s.detail && <div className="mt-1 text-sm text-fg-muted">{s.detail}</div>}
          </div>
        </li>
      ))}
    </ol>
  )
}

interface MessageDrawerProps {
  open: boolean
  onClose: () => void
  /** Kept while the drawer animates out. */
  message: Message | null
  deviceName: DeviceName
}

/** Details of one message: its status timeline and identifiers. */
export function MessageDrawer({ open, onClose, message: m, deviceName }: MessageDrawerProps) {
  const device = deviceName === null ? 'a removed device' : (deviceName ?? 'the phone')
  return (
    <Drawer
      open={open}
      onClose={onClose}
      title={m ? <span className="font-mono tabular-nums">{m.to}</span> : 'Message'}
      description={m ? <StatusBadge status={m.status} /> : undefined}
    >
      {m && (
        <div className="flex flex-col gap-8">
          <section aria-labelledby="message-timeline">
            <h3 id="message-timeline" className="mb-4 text-xs font-medium tracking-wide text-fg-muted uppercase">
              Timeline
            </h3>
            <Timeline message={m} device={device} />
          </section>

          <section aria-labelledby="message-details">
            <h3 id="message-details" className="mb-3 text-xs font-medium tracking-wide text-fg-muted uppercase">
              Details
            </h3>
            <dl className="divide-y divide-border rounded-xl border border-border bg-surface text-sm">
              <Detail label="Message ID">
                <span className="flex items-center gap-1">
                  <code className="min-w-0 truncate font-mono text-xs text-fg">{m.id}</code>
                  <CopyButton value={m.id} label="Copy message ID" className="-my-1 shrink-0" />
                </span>
              </Detail>
              <Detail label="Device">
                {deviceName ??
                  (deviceName === null ? (
                    <span className="text-fg-subtle">Removed device</span>
                  ) : (
                    <span className="text-fg-subtle">
                      <span aria-hidden="true">—</span>
                      <span className="sr-only">Device not loaded</span>
                    </span>
                  ))}
              </Detail>
              <Detail label="SIM">{m.sim_slot === null ? 'Phone default' : `SIM ${m.sim_slot + 1}`}</Detail>
              <Detail label="Expires">
                <time dateTime={m.expires_at}>{absoluteTime(m.expires_at)}</time>
              </Detail>
              {m.client_ref && (
                <Detail label="Client ref">
                  <code className="font-mono text-xs break-all text-fg">{m.client_ref}</code>
                </Detail>
              )}
            </dl>
          </section>

          <p className="flex items-start gap-2 text-xs leading-5 text-fg-subtle">
            <InfoIcon className="mt-0.5 size-3.5 shrink-0" />
            The message text isn’t shown: the API never returns it once the message is queued.
          </p>
        </div>
      )}
    </Drawer>
  )
}

function Detail({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[6.5rem_minmax(0,1fr)] items-center gap-3 px-3.5 py-2.5">
      <dt className="text-fg-muted">{label}</dt>
      <dd className="min-w-0 text-fg">{children}</dd>
    </div>
  )
}
