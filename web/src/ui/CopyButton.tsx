import { useEffect, useRef, useState } from 'react'
import { cx, focusRing } from './cx'
import { CopyIcon } from './icons'

interface CopyButtonProps {
  value: string
  /** Accessible label in the idle state. */
  label?: string
  /** Show the label text next to the icon. */
  showLabel?: boolean
  className?: string
  onCopied?: () => void
}

/** Copies `value`; the copy glyph morphs into a drawn check for 1.6 s. */
export function CopyButton({ value, label = 'Copy', showLabel = false, className, onCopied }: CopyButtonProps) {
  const [copied, setCopied] = useState(false)
  const timer = useRef<number | undefined>(undefined)

  useEffect(() => () => window.clearTimeout(timer.current), [])

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(value)
    } catch {
      return // clipboard denied: leave the value visible for manual copy
    }
    setCopied(true)
    onCopied?.()
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => setCopied(false), 1600)
  }

  const text = copied ? 'Copied' : label

  return (
    <button
      type="button"
      onClick={copy}
      aria-label={showLabel ? undefined : text}
      className={cx(
        'inline-flex h-8 items-center gap-1.5 rounded-lg px-2 text-[13px] font-medium',
        'transition-[background-color,color,scale] duration-150 ease-out motion-safe:active:scale-95',
        'motion-reduce:transition-none motion-reduce:scale-none',
        copied ? 'text-success-600 dark:text-success-400' : 'text-fg-muted hover:bg-surface-hover hover:text-fg',
        focusRing,
        className,
      )}
    >
      <span className="relative inline-flex size-4 text-base">
        <CopyIcon
          className={cx(
            'absolute inset-0 transition-[opacity,scale] duration-200 ease-out motion-reduce:transition-none motion-reduce:scale-none',
            copied ? 'scale-50 opacity-0' : 'scale-100 opacity-100',
          )}
        />
        <svg
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth={2.25}
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
          className={cx(
            'absolute inset-0 transition-[opacity,scale] duration-200 ease-out motion-reduce:transition-none motion-reduce:scale-none',
            copied ? 'scale-100 opacity-100' : 'scale-50 opacity-0',
          )}
        >
          {copied && (
            <path d="m5 12.5 4.5 4.5L19 7.5" strokeDasharray="24" className="animate-check motion-reduce:animate-none" />
          )}
        </svg>
      </span>
      {showLabel && <span>{text}</span>}
    </button>
  )
}
