import type { ReactNode } from 'react'
import { cx } from './cx'
import { InboxIcon } from './icons'

interface EmptyStateProps {
  icon?: ReactNode
  title: ReactNode
  description?: ReactNode
  action?: ReactNode
  className?: string
}

export function EmptyState({ icon, title, description, action, className }: EmptyStateProps) {
  return (
    <div className={cx('flex flex-col items-center px-6 py-14 text-center', className)}>
      <div className="relative mb-5">
        <div className="absolute inset-0 rounded-2xl bg-brand-500/20 blur-xl" aria-hidden="true" />
        <div className="relative flex size-12 items-center justify-center rounded-2xl border border-border bg-surface-raised text-2xl text-brand-600 shadow-raised dark:text-brand-400">
          {icon ?? <InboxIcon />}
        </div>
      </div>
      <h3 className="text-[15px] font-semibold tracking-tight text-fg">{title}</h3>
      {description && <p className="mt-1.5 max-w-sm text-sm text-pretty text-fg-muted">{description}</p>}
      {action && <div className="mt-6">{action}</div>}
    </div>
  )
}
