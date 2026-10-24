import { useId } from 'react'
import type { MessageStatus } from '../api/types'
import type { BadgeTone } from '../ui/Badge'
import { cx } from '../ui/cx'
import { messageStatuses, statusDescription, statusFill, statusLabel, statusTone } from '../ui/messageStatus'

// Node surfaces per badge tone, as SVG fill/stroke token classes.
const nodeTone: Record<BadgeTone, string> = {
  neutral: 'fill-surface-sunken stroke-border-strong',
  brand: 'fill-brand-50 stroke-brand-300 dark:fill-brand-950 dark:stroke-brand-700',
  success: 'fill-success-50 stroke-success-300 dark:fill-success-950 dark:stroke-success-800',
  warning: 'fill-warning-50 stroke-warning-300 dark:fill-warning-950 dark:stroke-warning-800',
  danger: 'fill-danger-50 stroke-danger-300 dark:fill-danger-950 dark:stroke-danger-800',
}

const dotFill: Record<MessageStatus, string> = {
  queued: 'fill-ink-400 dark:fill-ink-500',
  dispatched: 'fill-brand-300 dark:fill-brand-600',
  sent: 'fill-brand-500',
  delivered: 'fill-success-500',
  failed: 'fill-danger-500',
  expired: 'fill-warning-500',
}

const W = 136
const H = 40
const pos: Record<MessageStatus, [number, number]> = {
  queued: [16, 72],
  dispatched: [240, 72],
  sent: [464, 72],
  delivered: [688, 72],
  expired: [16, 196],
  failed: [352, 196],
}

function Node({ status }: { status: MessageStatus }) {
  const [x, y] = pos[status]
  return (
    <g>
      <rect x={x} y={y} width={W} height={H} rx={12} strokeWidth={1.25} className={nodeTone[statusTone[status]]} />
      <circle cx={x + 20} cy={y + H / 2} r={4} className={dotFill[status]} />
      <text x={x + 32} y={y + H / 2 + 4.5} className="fill-fg text-[13px] font-medium">
        {statusLabel[status]}
      </text>
    </g>
  )
}

function Label({ x, y, children, anchor = 'middle' }: { x: number; y: number; children: string; anchor?: 'start' | 'middle' }) {
  return (
    <text x={x} y={y} textAnchor={anchor} className="fill-fg-muted text-[11px]">
      {children}
    </text>
  )
}

/** queued → dispatched → sent → delivered, with the expired/failed exits. */
export function LifecycleDiagram() {
  const id = useId()
  const arrow = `${id}-arrow`
  const mid = H / 2
  const edge = 'stroke-fg-subtle'
  return (
    <figure className="space-y-5">
      <div className="overflow-x-auto rounded-xl border border-border bg-surface-sunken/60 p-3 sm:p-4">
        <svg
          viewBox="0 0 840 268"
          role="img"
          aria-labelledby={`${id}-title`}
          className="h-auto w-full min-w-[600px]"
          fill="none"
        >
          <title id={`${id}-title`}>
            Message lifecycle: queued, then dispatched, sent and delivered. A queued message not picked up in time
            becomes expired; a dispatched or sent message can become failed.
          </title>
          <defs>
            <marker id={arrow} viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M1 1 9 5 1 9Z" className="fill-fg-subtle" />
            </marker>
          </defs>

          <g strokeWidth={1.5} markerEnd={`url(#${arrow})`}>
            {/* happy path */}
            <path d={`M${16 + W} ${72 + mid}H238`} className={edge} />
            <path d={`M${240 + W} ${72 + mid}H462`} className={edge} />
            <path d={`M${464 + W} ${72 + mid}H686`} className={edge} />
            {/* the delivery report can overtake the sent report */}
            <path d="M308 70C308 14 756 14 756 70" strokeDasharray="4 4" className={edge} />
            {/* exits */}
            <path d={`M84 ${72 + H}V194`} className={edge} />
            <path d={`M308 ${72 + H}L392 194`} className={edge} />
            <path d={`M532 ${72 + H}L448 194`} className={edge} />
          </g>

          <Label x={196} y={84}>picks up</Label>
          <Label x={420} y={84}>to carrier</Label>
          <Label x={644} y={84}>report</Label>
          <Label x={532} y={50}>delivery report may arrive first</Label>
          <Label x={94} y={158} anchor="start">not picked up</Label>
          <Label x={94} y={172} anchor="start">by expires_at</Label>
          <Label x={420} y={256}>phone error or device_lost</Label>

          {messageStatuses.map((s) => (
            <Node key={s} status={s} />
          ))}
        </svg>
      </div>

      <figcaption>
        <ul className="grid gap-x-6 gap-y-2.5 sm:grid-cols-2">
          {messageStatuses.map((s) => (
            <li key={s} className="flex gap-2.5 text-sm">
              <span aria-hidden="true" className={cx('mt-1.5 size-2 shrink-0 rounded-full', statusFill[s])} />
              <span>
                <span className="font-medium text-fg">{statusLabel[s]}</span>{' '}
                <span className="text-fg-muted">{statusDescription[s]}</span>
              </span>
            </li>
          ))}
        </ul>
      </figcaption>
    </figure>
  )
}
