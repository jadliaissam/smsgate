import { useId, type ReactNode } from 'react'
import { Button } from '../ui/Button'
import { cx } from '../ui/cx'

interface VerifiedButtonProps {
  verified: boolean
  onClick: () => void
  icon?: ReactNode
  label: string
  /** Tooltip shown on the disabled button, e.g. "Verify your email to …". */
  reason: string
  tooltipAlign?: 'end' | 'center'
  className?: string
}

/**
 * A primary action that needs a verified email (API keys, sending): until
 * then it is shown disabled, with the reason in a tooltip.
 */
export function VerifiedButton({
  verified,
  onClick,
  icon,
  label,
  reason,
  tooltipAlign = 'end',
  className,
}: VerifiedButtonProps) {
  const tipId = useId()
  if (verified) {
    return (
      <Button icon={icon} onClick={onClick} className={className}>
        {label}
      </Button>
    )
  }
  // aria-disabled (not disabled) keeps it focusable so keyboard users reach
  // the reason; it has no click handler. Hover lands on the wrapper.
  return (
    <span className="group/tip relative inline-flex">
      <Button
        icon={icon}
        aria-disabled="true"
        aria-describedby={tipId}
        className={cx('pointer-events-none opacity-55', className)}
      >
        {label}
      </Button>
      <span
        id={tipId}
        role="tooltip"
        className={cx(
          'pointer-events-none absolute top-full z-30 mt-2 w-60 rounded-xl border border-border bg-surface-raised px-3 py-2 text-left text-xs leading-5 font-normal whitespace-normal text-fg shadow-overlay',
          'invisible opacity-0 transition-[opacity,visibility] duration-150 ease-out motion-reduce:transition-none',
          'group-hover/tip:visible group-hover/tip:opacity-100 group-focus-within/tip:visible group-focus-within/tip:opacity-100',
          // Centered without a transform: half the w-60 width as a negative margin.
          tooltipAlign === 'end' ? 'right-0' : 'left-1/2 -ml-30',
        )}
      >
        {reason}
      </span>
    </span>
  )
}
