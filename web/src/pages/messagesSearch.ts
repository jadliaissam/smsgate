import type { MessageStatus } from '../api/types'

/** URL search params of /messages: the log's filters. */
export interface MessagesSearch {
  status?: MessageStatus
  device?: string
}

const statuses = new Set<string>(['queued', 'dispatched', 'sent', 'delivered', 'failed', 'expired'])

/** Keeps known statuses and a non-empty device id; drops anything else. */
export function validateMessagesSearch(s: Record<string, unknown>): MessagesSearch {
  const out: MessagesSearch = {}
  if (typeof s.status === 'string' && statuses.has(s.status)) out.status = s.status as MessageStatus
  if (typeof s.device === 'string' && s.device !== '') out.device = s.device
  return out
}
