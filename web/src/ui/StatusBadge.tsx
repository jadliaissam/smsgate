import type { MessageStatus } from '../api/types'
import { Badge } from './Badge'
import { statusDescription, statusLabel, statusTone } from './messageStatus'

/** Message status chip; in-flight statuses get a pulsing dot. */
export function StatusBadge({ status, className }: { status: MessageStatus; className?: string }) {
  const live = status === 'queued' || status === 'dispatched'
  return (
    <Badge tone={statusTone[status]} dot pulse={live} className={className}>
      <span title={statusDescription[status]}>{statusLabel[status]}</span>
    </Badge>
  )
}
