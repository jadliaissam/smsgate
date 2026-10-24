import { cx } from './cx'

interface SkeletonProps {
  className?: string
  /** Rounded-full (avatars, dots). */
  circle?: boolean
}

/** Shimmering placeholder; static tint under reduced motion. */
export function Skeleton({ className, circle = false }: SkeletonProps) {
  return (
    <div
      aria-hidden="true"
      className={cx(
        'bg-surface-sunken bg-linear-to-r from-ink-100 via-ink-200/70 to-ink-100 bg-size-[200%_100%]',
        'dark:from-ink-900 dark:via-ink-800 dark:to-ink-900',
        'animate-shimmer motion-reduce:animate-none',
        circle ? 'rounded-full' : 'rounded-lg',
        className ?? 'h-4 w-full',
      )}
    />
  )
}
