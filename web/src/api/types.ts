// TypeScript mirrors of the backend JSON (see backend/internal/*). Timestamps
// are RFC 3339 strings; ids are UUID strings.

/** GET /app/me */
export interface Me {
  id: string
  email: string
  email_verified: boolean
  plan: string
}

/** One SIM as reported by the phone. */
export interface SIM {
  slot: number
  subscription_id: number
  carrier: string
  number?: string
}

/** GET /app/devices item */
export interface Device {
  id: string
  name: string
  online: boolean
  sims: SIM[]
  battery: number | null
  charging: boolean | null
  last_seen_at: string | null
}

/** POST /app/pairing-codes */
export interface PairingCode {
  code: string
  expires_at: string
  qr_payload: string
}

/** A pairing code as the dashboard tracks it, with a skew-free lifetime. */
export interface IssuedPairingCode extends PairingCode {
  /** Client clock when the response arrived (epoch ms). */
  receivedAt: number
  /** Lifetime left at that moment: expires_at minus the server's Date. */
  ttlMs: number
}

/** GET /app/api-keys item */
export interface ApiKey {
  id: string
  name: string
  prefix: string
  created_at: string
  last_used_at: string | null
  revoked_at: string | null
}

/** POST /app/api-keys: the only response that ever contains the full key. */
export interface CreatedApiKey extends ApiKey {
  key: string
}

export type MessageStatus = 'queued' | 'dispatched' | 'sent' | 'delivered' | 'failed' | 'expired'

export const terminalStatuses: readonly MessageStatus[] = ['delivered', 'failed', 'expired']

/** GET /app/messages item (body is never returned by the list/get API). */
export interface Message {
  id: string
  device_id: string
  sim_slot: number | null
  to: string
  status: MessageStatus
  client_ref: string | null
  error_code: string | null
  error_message: string | null
  created_at: string
  expires_at: string
  dispatched_at: string | null
  sent_at: string | null
  delivered_at: string | null
  failed_at: string | null
}

/** POST /app/messages body */
export interface SendMessageInput {
  device_id: string
  to: string
  body: string
  sim_slot?: number
  ttl_seconds?: number
  client_ref?: string
}

/** List envelope: `next_before` is the cursor for the next (older) page. */
export interface Page<T> {
  data: T[]
  next_before?: string | null
}
