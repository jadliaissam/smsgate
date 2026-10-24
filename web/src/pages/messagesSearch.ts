import type { MessageStatus } from '../api/types'

/** URL search params of /messages: the log's filters. */
export interface MessagesSearch {
  status?: MessageStatus
  device?: string
  /** Open the send-test modal on arrival (links from the Overview). */
  send?: true
  /** Open the details drawer of this message id on arrival. */
  message?: string
}

const statuses = new Set<string>(['queued', 'dispatched', 'sent', 'delivered', 'failed', 'expired'])

/** Keeps known statuses, a non-empty device/message id and the send flag; drops anything else. */
export function validateMessagesSearch(s: Record<string, unknown>): MessagesSearch {
  const out: MessagesSearch = {}
  if (typeof s.status === 'string' && statuses.has(s.status)) out.status = s.status as MessageStatus
  if (typeof s.device === 'string' && s.device !== '') out.device = s.device
  if (s.send === true || s.send === 'true' || s.send === '1' || s.send === 1) out.send = true
  if (typeof s.message === 'string' && s.message !== '') out.message = s.message
  return out
}
