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

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** True for a UUID-shaped string (device and message ids). */
export function isUuid(v: unknown): v is string {
  return typeof v === 'string' && uuid.test(v)
}

const statuses = new Set<string>(['queued', 'dispatched', 'sent', 'delivered', 'failed', 'expired'])

/** Keeps known statuses, UUID-shaped device/message ids and the send flag; drops anything else. */
export function validateMessagesSearch(s: Record<string, unknown>): MessagesSearch {
  // Every key is set (undefined when invalid): the router merges this over
  // the raw search inherited from the root route, so an omitted key would
  // let the raw, unvalidated value through.
  const out: MessagesSearch = { status: undefined, device: undefined, send: undefined, message: undefined }
  if (typeof s.status === 'string' && statuses.has(s.status)) out.status = s.status as MessageStatus
  if (isUuid(s.device)) out.device = s.device
  if (s.send === true || s.send === 'true' || s.send === '1' || s.send === 1) out.send = true
  if (isUuid(s.message)) out.message = s.message
  return out
}
