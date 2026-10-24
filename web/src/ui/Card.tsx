import type { HTMLAttributes, ReactNode } from 'react'
import { cx } from './cx'

export interface CardProps extends HTMLAttributes<HTMLDivElement> {
  /** Hover lift for clickable cards. */
  interactive?: boolean
  /** Inner padding; "none" for cards that manage their own layout (tables). */
  padding?: 'none' | 'sm' | 'md' | 'lg'
}

const paddings = { none: '', sm: 'p-4', md: 'p-5 sm:p-6', lg: 'p-6 sm:p-8' }

export function Card({ interactive = false, padding = 'md', className, ...rest }: CardProps) {
  return (
    <div
      className={cx(
        'relative rounded-2xl border border-border bg-surface shadow-card',
        paddings[padding],
        interactive &&
          'transition-[box-shadow,translate,border-color] duration-200 ease-out motion-safe:hover:-translate-y-0.5 hover:border-border-strong hover:shadow-raised motion-reduce:transition-none',
        className,
      )}
      {...rest}
    />
  )
}

interface CardHeaderProps {
  title: ReactNode
  description?: ReactNode
  actions?: ReactNode
  className?: string
}

export function CardHeader({ title, description, actions, className }: CardHeaderProps) {
  return (
    <div className={cx('mb-5 flex items-start justify-between gap-4', className)}>
      <div className="min-w-0">
        <h3 className="text-[15px] font-semibold tracking-tight text-fg">{title}</h3>
        {description && <p className="mt-1 text-sm text-fg-muted">{description}</p>}
      </div>
      {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
    </div>
  )
}
