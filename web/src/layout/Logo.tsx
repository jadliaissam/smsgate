import { cx } from '../ui/cx'

/** Brand mark: a message bubble with signal arcs on the brand gradient tile. */
export function LogoMark({ className }: { className?: string }) {
  return (
    <span
      aria-hidden="true"
      className={cx(
        'inline-flex size-8 shrink-0 items-center justify-center rounded-[10px] bg-linear-to-br from-brand-400 via-brand-600 to-brand-800 text-fg-on-brand shadow-glow inset-ring inset-ring-white/15',
        className,
      )}
    >
      <svg viewBox="0 0 24 24" fill="none" className="size-[62%]" focusable="false">
        <path
          d="M5 6.5A2.5 2.5 0 0 1 7.5 4h9A2.5 2.5 0 0 1 19 6.5v6a2.5 2.5 0 0 1-2.5 2.5H11l-4 3.5V15h0.5A2.5 2.5 0 0 1 5 12.5Z"
          fill="currentColor"
          fillOpacity="0.95"
        />
        <path
          d="M9.25 9.5h5.5M9.25 12h3"
          stroke="currentColor"
          strokeWidth="1.6"
          strokeLinecap="round"
          className="text-brand-700"
        />
        <path d="M19.5 2.75a4 4 0 0 1 1.75 3.25" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeOpacity="0.7" />
      </svg>
    </span>
  )
}

/** Mark + "SMSGate" wordmark. */
export function Wordmark({ className, size = 'md' }: { className?: string; size?: 'md' | 'lg' }) {
  return (
    <span className={cx('inline-flex items-center gap-2.5', className)}>
      <LogoMark className={size === 'lg' ? 'size-10 rounded-xl' : undefined} />
      <span
        className={cx('font-semibold tracking-tight text-fg', size === 'lg' ? 'text-xl' : 'text-[17px]')}
      >
        SMS<span className="text-brand-600 dark:text-brand-400">Gate</span>
      </span>
    </span>
  )
}
