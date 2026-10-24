/** Joins truthy class names. */
export function cx(...parts: (string | false | null | undefined)[]): string {
  return parts.filter(Boolean).join(' ')
}

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger'
export type ButtonSize = 'sm' | 'md'

const base =
  'relative inline-flex select-none items-center justify-center gap-2 whitespace-nowrap font-medium ' +
  'transition-[background-color,border-color,color,box-shadow,scale] duration-150 ease-out motion-safe:active:scale-[0.98] ' +
  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500/60 focus-visible:ring-offset-2 focus-visible:ring-offset-canvas ' +
  'disabled:pointer-events-none disabled:opacity-55 ' +
  'motion-reduce:transition-none'

const variants: Record<ButtonVariant, string> = {
  primary:
    'bg-brand-600 text-fg-on-brand shadow-control inset-ring inset-ring-white/10 ' +
    'bg-linear-to-b from-brand-500 to-brand-600 hover:from-brand-500 hover:to-brand-700 hover:shadow-glow',
  secondary:
    'bg-surface text-fg border border-border shadow-control hover:bg-surface-hover hover:border-border-strong',
  ghost: 'text-fg-muted hover:bg-surface-hover hover:text-fg',
  danger:
    'bg-danger-600 text-white shadow-control inset-ring inset-ring-white/10 hover:bg-danger-700 ' +
    'focus-visible:ring-danger-500/60',
}

const sizes: Record<ButtonSize, string> = {
  sm: 'h-8 rounded-lg px-3 text-[13px]',
  md: 'h-10 rounded-xl px-4 text-sm',
}

/** Button look for non-button elements (e.g. router links). */
export function buttonStyles(variant: ButtonVariant = 'primary', size: ButtonSize = 'md'): string {
  return cx(base, variants[variant], sizes[size])
}

/** Shared focus ring for interactive elements. */
export const focusRing =
  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500/60 focus-visible:ring-offset-2 focus-visible:ring-offset-canvas'
