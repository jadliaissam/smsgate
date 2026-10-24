import { useState } from 'react'
import type { Message } from '../api/types'
import { ChevronDownIcon } from '../layout/icons'
import { cx, focusRing } from '../ui/cx'
import { maskNumber, statusLabel } from '../ui/messageStatus'
import { StatusBadge } from '../ui/StatusBadge'
import { absoluteTime, sinceTime } from '../ui/time'

// Status | to | device | created | chevron on md and up; stacked below.
const columns = 'md:grid-cols-[7.5rem_minmax(0,1.2fr)_minmax(0,1fr)_minmax(0,0.8fr)_1rem]'
const compactColumns = 'md:grid-cols-[7.5rem_minmax(0,1fr)_minmax(0,0.8fr)_1rem]'

/**
 * A phone number shown as "+•••••••4567" until the row is hovered or
 * focused. Screen readers always get the full number.
 */
export function MaskedNumber({ to, className }: { to: string; className?: string }) {
  return (
    <span className={cx('font-mono tabular-nums', className)}>
      <span aria-hidden="true" className="group-hover/row:hidden group-focus-visible/row:hidden">
        {maskNumber(to)}
      </span>
      <span className="sr-only group-hover/row:not-sr-only group-focus-visible/row:not-sr-only">{to}</span>
    </span>
  )
}

/** Column headings for a list of MessageRow (md and up). */
export function MessageListHeader({ compact = false }: { compact?: boolean }) {
  return (
    <div
      aria-hidden="true"
      className={cx(
        'hidden gap-4 border-b border-border bg-surface-sunken/60 px-6 py-2.5 text-xs font-medium text-fg-muted md:grid',
        compact ? compactColumns : columns,
      )}
    >
      <span>Status</span>
      <span>To</span>
      {!compact && <span>Device</span>}
      <span>Created</span>
      <span />
    </div>
  )
}

export interface MessageRowProps {
  message: Message
  /** The sending device's name; undefined when it is no longer paired. */
  deviceName: string | undefined
  /** Clock for the relative time (pass useNow()). */
  now: number
  /** Makes the row a button (e.g. to open the details drawer). */
  onSelect?: (m: Message) => void
  /** Briefly highlight the row (a message that was just sent). */
  flash?: boolean
  /** Hide the device column (narrow lists such as the Overview). */
  compact?: boolean
}

/** One message in a list: status, masked recipient, device, created. Render inside a <ul>. */
export function MessageRow({ message: m, deviceName, now, onSelect, flash = false, compact = false }: MessageRowProps) {
  const device = deviceName ?? 'Removed device'
  // Once flashed, keep the class: switching back to fade-in would replay it.
  const [flashed, setFlashed] = useState(flash)
  if (flash && !flashed) setFlashed(true)
  const body = (
    <>
      <div className="col-start-2 row-start-1 flex justify-end md:col-auto md:row-auto md:justify-start">
        <StatusBadge status={m.status} />
      </div>
      <div className="col-start-1 row-start-1 min-w-0 md:col-auto md:row-auto">
        <MaskedNumber to={m.to} className="block truncate text-sm font-medium text-fg" />
      </div>
      {!compact && (
        <div className={cx('col-start-1 row-start-2 min-w-0 truncate text-sm md:col-auto md:row-auto', deviceName ? 'text-fg' : 'text-fg-subtle')}>
          {device}
        </div>
      )}
      <div
        className={cx(
          'row-start-2 text-sm text-fg-muted md:col-auto md:row-auto md:text-left',
          compact ? 'col-span-2 col-start-1' : 'col-start-2 text-right',
        )}
      >
        <time dateTime={m.created_at} title={absoluteTime(m.created_at)}>
          {sinceTime(m.created_at, now)}
        </time>
      </div>
      <ChevronDownIcon
        aria-hidden="true"
        className={cx(
          'hidden size-4 -rotate-90 text-fg-subtle md:block',
          'transition-[translate,color] duration-150 ease-out group-hover/row:text-fg-muted motion-safe:group-hover/row:translate-x-0.5 motion-reduce:transition-none',
          !onSelect && 'invisible',
        )}
      />
    </>
  )

  const layout = cx(
    'grid w-full grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-1 px-4 py-3.5 text-left sm:px-5 md:gap-y-0 md:px-6 md:py-3',
    compact ? compactColumns : columns,
  )

  return (
    <li
      data-message-id={m.id}
      data-flash={flash || undefined}
      className={cx(flashed ? 'animate-flash' : 'animate-fade-in', 'motion-reduce:animate-none')}
    >
      {onSelect ? (
        <button
          type="button"
          onClick={() => onSelect(m)}
          aria-label={`${statusLabel[m.status]} message to ${m.to}, from ${device}, ${sinceTime(m.created_at, now)}`}
          className={cx(
            'group/row',
            layout,
            'transition-colors duration-150 hover:bg-surface-hover/60 motion-reduce:transition-none',
            focusRing,
            'focus-visible:ring-offset-0 focus-visible:ring-inset',
          )}
        >
          {body}
        </button>
      ) : (
        <div className={cx('group/row', layout)}>
          {body}
        </div>
      )}
    </li>
  )
}
