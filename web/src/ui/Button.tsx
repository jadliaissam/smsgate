import type { ButtonHTMLAttributes, ReactNode, Ref } from 'react'
import { buttonStyles, cx, type ButtonSize, type ButtonVariant } from './cx'
import { Spinner } from './Spinner'

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  size?: ButtonSize
  /** Shows a spinner and disables the button (mutation in flight). */
  loading?: boolean
  /** Optional leading icon (hidden while loading). */
  icon?: ReactNode
  ref?: Ref<HTMLButtonElement>
}

export function Button({
  variant = 'primary',
  size = 'md',
  loading = false,
  icon,
  disabled,
  type = 'button',
  className,
  children,
  ...rest
}: ButtonProps) {
  return (
    <button
      type={type}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={cx(buttonStyles(variant, size), className)}
      {...rest}
    >
      {loading ? <Spinner size="sm" decorative /> : icon ? <span className="-ml-0.5 text-[1.1em]">{icon}</span> : null}
      {children}
    </button>
  )
}
