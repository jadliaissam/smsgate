import type { ReactNode } from 'react'
import { cx } from './cx'

export type BadgeTone = 'neutral' | 'brand' | 'success' | 'warning' | 'danger'

interface BadgeProps {
  tone?: BadgeTone
  /** Leading status dot. */
  dot?: boolean
  /** Animate the dot (live status, e.g. device online). */
  pulse?: boolean
  className?: string
  children: ReactNode
}

const tones: Record<BadgeTone, { chip: string; dot: string }> = {
  neutral: {
    chip: 'bg-surface-sunken text-fg-muted inset-ring-border',
    dot: 'bg-fg-subtle',
  },
  brand: {
    chip: 'bg-brand-50 text-brand-700 inset-ring-brand-600/15 dark:bg-brand-500/12 dark:text-brand-300 dark:inset-ring-brand-400/20',
    dot: 'bg-brand-500',
  },
  success: {
    chip: 'bg-success-50 text-success-700 inset-ring-success-600/15 dark:bg-success-500/12 dark:text-success-300 dark:inset-ring-success-400/20',
    dot: 'bg-success-500',
  },
  warning: {
    chip: 'bg-warning-50 text-warning-800 inset-ring-warning-600/20 dark:bg-warning-500/12 dark:text-warning-300 dark:inset-ring-warning-400/20',
    dot: 'bg-warning-500',
  },
  danger: {
    chip: 'bg-danger-50 text-danger-700 inset-ring-danger-600/15 dark:bg-danger-500/12 dark:text-danger-300 dark:inset-ring-danger-400/20',
    dot: 'bg-danger-500',
  },
}

export function Badge({ tone = 'neutral', dot = false, pulse = false, className, children }: BadgeProps) {
  const t = tones[tone]
  return (
    <span
      className={cx(
        'inline-flex h-6 items-center gap-1.5 rounded-full px-2.5 text-xs font-medium whitespace-nowrap inset-ring',
        t.chip,
        className,
      )}
    >
      {(dot || pulse) && (
        <span className="relative flex size-1.5" aria-hidden="true">
          {pulse && (
            <span
              className={cx('absolute inset-0 rounded-full animate-ping-soft motion-reduce:animate-none', t.dot)}
            />
          )}
          <span className={cx('relative size-1.5 rounded-full', t.dot)} />
        </span>
      )}
      {children}
    </span>
  )
}
