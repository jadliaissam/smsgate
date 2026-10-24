import { cx } from './cx'

interface SpinnerProps {
  size?: 'sm' | 'md' | 'lg'
  label?: string
  className?: string
}

const sizes = { sm: 'size-4', md: 'size-5', lg: 'size-8' }

/** Indeterminate progress ring. Static (no rotation) under reduced motion. */
export function Spinner({ size = 'sm', label = 'Loading', className }: SpinnerProps) {
  return (
    <span role="status" aria-label={label} className={cx('inline-flex shrink-0', sizes[size], className)}>
      <svg viewBox="0 0 24 24" fill="none" className="size-full animate-spin motion-reduce:animate-none">
        <circle cx="12" cy="12" r="9.5" stroke="currentColor" strokeOpacity="0.2" strokeWidth="2.5" />
        <path d="M21.5 12A9.5 9.5 0 0 0 12 2.5" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" />
      </svg>
    </span>
  )
}
