/**
 * User-facing copy for backend error codes, in one place. Codes come from
 * backend/internal/{accounts,devices,messages,httpx,ratelimit}.
 */
const copy: Record<string, string> = {
  // auth & account
  invalid_credentials: 'That email or password doesn’t look right.',
  email_taken: 'An account with this email already exists. Try signing in instead.',
  weak_password: 'Choose a password of at least 10 characters (up to 128).',
  invalid_email: 'Enter a valid email address.',
  email_not_verified: 'Verify your email address first — check your inbox for the link.',
  invalid_token: 'This link is invalid or has expired. Request a new one.',
  unauthorized: 'Your session has expired. Please sign in again.',

  // devices & keys
  invalid_name: 'Names must be between 1 and 64 characters.',
  device_not_found: 'That device no longer exists — it may have been revoked.',
  api_key_not_found: 'That API key no longer exists.',

  // messages
  invalid_to: 'Enter the number in international format, e.g. +37060000000.',
  body_empty: 'Write a message before sending.',
  body_too_long: 'This message is too long — keep it within 3 SMS segments.',
  invalid_body: 'The message contains characters that can’t be sent.',
  invalid_ttl: 'Choose a valid expiry time.',
  invalid_sim_slot: 'That SIM isn’t available on this device.',
  idempotency_mismatch: 'This request was already sent with different details. Reopen the form and try again.',
  message_not_found: 'That message no longer exists.',

  // transport & limits
  rate_limited: 'Too many attempts. Wait a moment and try again.',
  busy: 'The server is busy right now. Try again in a few seconds.',
  unsupported_media_type: 'The request was rejected by the server. Refresh the page and try again.',
  network_error: 'Can’t reach the server. Check your connection and try again.',
  invalid_response: 'The server sent an unexpected response. Try again.',
  internal: 'Something went wrong on our side. Try again in a moment.',
}

const fallback = 'Something went wrong. Please try again.'
const unavailable = 'The service is temporarily unavailable. Try again in a moment.'

export function errorCopy(code: string): string {
  const known = copy[code]
  if (known) return known
  if (/^http_5\d\d$/.test(code)) return unavailable
  return fallback
}
