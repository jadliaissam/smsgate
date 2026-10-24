import type { HTMLAttributes } from 'react'
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
