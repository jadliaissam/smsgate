import type { MessageStatus } from '../api/types'
import type { BadgeTone } from './Badge'

/** Every message status, in lifecycle order. */
export const messageStatuses: readonly MessageStatus[] = ['queued', 'dispatched', 'sent', 'delivered', 'failed', 'expired']

/** Badge tone per status (also used by charts/legends on the Overview). */
export const statusTone: Record<MessageStatus, BadgeTone> = {
  queued: 'neutral',
  dispatched: 'brand',
  sent: 'brand',
  delivered: 'success',
  failed: 'danger',
  expired: 'warning',
}

export const statusLabel: Record<MessageStatus, string> = {
  queued: 'Queued',
  dispatched: 'Dispatched',
  sent: 'Sent',
  delivered: 'Delivered',
  failed: 'Failed',
  expired: 'Expired',
}

/** What each status means, for tooltips and the timeline. */
export const statusDescription: Record<MessageStatus, string> = {
  queued: 'Waiting for the phone to pick it up.',
  dispatched: 'The phone picked it up and is sending it.',
  sent: 'The phone handed it to the carrier.',
  delivered: 'The carrier confirmed delivery to the recipient.',
  failed: 'The phone could not send it.',
  expired: 'The phone did not pick it up before it expired.',
}

/** Solid fill per status (dots, bars, legends): token classes only. */
export const statusFill: Record<MessageStatus, string> = {
  queued: 'bg-ink-400 dark:bg-ink-500',
  dispatched: 'bg-brand-300 dark:bg-brand-700',
  sent: 'bg-brand-500',
  delivered: 'bg-success-500',
  failed: 'bg-danger-500',
  expired: 'bg-warning-500',
}

/**
 * "+37061234567" → "+•••••••4567": the last four digits stay readable.
 * Short inputs are returned as is.
 */
export function maskNumber(to: string): string {
  if (to.length <= 5) return to
  const head = to.startsWith('+') ? '+' : ''
  return head + '•'.repeat(to.length - head.length - 4) + to.slice(-4)
}
