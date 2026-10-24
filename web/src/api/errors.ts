/**
 * User-facing copy for backend error codes, in one place. Codes come from
 * backend/internal/{accounts,devices,messages,httpx,ratelimit}.
 */
const copy: Record<string, string> = {
  // auth & account
  invalid_credentials: 'That email or password doesn’t look right.',
  email_taken: 'An account with this email already exists. Try signing in instead.',
  weak_password: 'That password is too short or too long. Choose a longer passphrase.',
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
  body_too_long: 'This message is too long. Shorten it and try again.',
  invalid_body: 'The message contains characters that can’t be sent.',
  invalid_ttl: 'Choose a valid expiry time.',
  invalid_sim_slot: 'That SIM isn’t available on this device.',
  idempotency_mismatch: 'A message from this form was already queued, so this one wasn’t sent again. Check the log, or start a new message to send another.',
  message_not_found: 'That message no longer exists.',
  invalid_idempotency_key: 'The request ID was rejected. Reopen the form and try again.',
  invalid_client_ref: 'The reference is too long. Shorten it and try again.',
  invalid_before: 'Couldn’t load more results. Refresh the list and try again.',
  invalid_limit: 'Couldn’t load this list. Refresh and try again.',
  invalid_status: 'That status filter isn’t valid. Clear the filter and try again.',
  invalid_since: 'That date filter isn’t valid. Clear the filter and try again.',
  invalid_device_id: 'That device filter isn’t valid. Clear the filter and try again.',

  // transport & limits
  rate_limited: 'Too many attempts. Wait a moment and try again.',
  busy: 'The server is busy right now. Try again in a few seconds.',
  unsupported_media_type: 'The request was rejected by the server. Refresh the page and try again.',
  network_error: 'Can’t reach the server. Check your connection and try again.',
  invalid_response: 'The server sent an unexpected response. Try again.',
  internal: 'Something went wrong on our side. Try again in a moment.',
  invalid_json: 'The request couldn’t be read by the server. Refresh the page and try again.',
  body_too_large: 'That request is too large. Shorten your input and try again.',
  route_not_found: 'This action isn’t available. Refresh the page to get the latest version.',
}

const fallback = 'Something went wrong. Please try again.'
const unavailable = 'The service is temporarily unavailable. Try again in a moment.'

export function errorCopy(code: string): string {
  const known = copy[code]
  if (known) return known
  if (code === 'cancelled' || /^http_5\d\d$/.test(code)) return unavailable
  return fallback
}
