import type { ReactNode } from 'react'
import { isApiError } from '../api/client'
import { errorCopy } from '../api/errors'
import { Button } from './Button'
import { Card } from './Card'
import { cx } from './cx'
import { AlertIcon } from './icons'

export interface QueryErrorCardProps {
  /** What failed, e.g. "Couldn’t load your devices". */
  title: string
  /** The query error; its code picks the copy from errors.ts. */
  error: unknown
  /** Show the Retry button as loading (the query's isFetching). */
  retrying: boolean
  onRetry: () => void
  /** An extra action next to Retry (e.g. "Clear filters"). */
  action?: ReactNode
  /**
   * "card": a standalone card for a whole page section. "inline": the same
   * content, smaller and without a card, for use inside another card.
   */
  variant?: 'card' | 'inline'
  className?: string
}

/** A failed query: what failed, why (mapped copy) and a Retry button. */
export function QueryErrorCard({
  title,
  error,
  retrying,
  onRetry,
  action,
  variant = 'card',
  className,
}: QueryErrorCardProps) {
  const inline = variant === 'inline'
  const content = (
    <>
      <span
        className={cx(
          'flex items-center justify-center rounded-2xl bg-danger-50 text-danger-600 dark:bg-danger-500/10 dark:text-danger-400',
          inline ? 'mb-3 size-10 text-lg' : 'mb-4 size-11 text-xl',
        )}
      >
        <AlertIcon />
      </span>
      {inline ? (
        <h3 className="text-sm font-semibold tracking-tight text-fg">{title}</h3>
      ) : (
        <h2 className="text-[15px] font-semibold tracking-tight text-fg">{title}</h2>
      )}
      <p className={cx('max-w-sm text-pretty text-fg-muted', inline ? 'mt-1 text-[13px]' : 'mt-1.5 text-sm')}>
        {errorCopy(isApiError(error) ? error.code : 'default')}
      </p>
      <div className={cx('flex flex-wrap justify-center gap-2', inline ? 'mt-4' : 'mt-6')}>
        <Button variant="secondary" size={inline ? 'sm' : 'md'} loading={retrying} onClick={onRetry}>
          Retry
        </Button>
        {action}
      </div>
    </>
  )
  const layout = 'flex flex-col items-center text-center'
  return inline ? (
    <div role="alert" className={cx(layout, className)}>
      {content}
    </div>
  ) : (
    <Card role="alert" className={cx(layout, 'py-12', className)}>
      {content}
    </Card>
  )
}
