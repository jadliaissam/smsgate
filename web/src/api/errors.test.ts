import { describe, expect, it } from 'vitest'
import { errorCopy } from './errors'

const known = [
  'invalid_credentials',
  'email_taken',
  'weak_password',
  'invalid_email',
  'email_not_verified',
  'invalid_name',
  'device_not_found',
  'invalid_to',
  'body_empty',
  'body_too_long',
  'invalid_ttl',
  'invalid_sim_slot',
  'idempotency_mismatch',
  'unauthorized',
  'rate_limited',
  'busy',
  'unsupported_media_type',
  'network_error',
]

describe('errorCopy', () => {
  it.each(known)('has specific copy for %s', (code) => {
    const copy = errorCopy(code)
    expect(copy).toBeTruthy()
    expect(copy).not.toBe(errorCopy('definitely_unknown_code'))
  })

  it('falls back to a generic message for unknown codes', () => {
    expect(errorCopy('definitely_unknown_code')).toMatch(/something went wrong/i)
    expect(errorCopy('')).toBe(errorCopy('definitely_unknown_code'))
  })

  it('treats gateway failures as a temporary outage', () => {
    expect(errorCopy('http_502')).toMatch(/unavailable/i)
    expect(errorCopy('http_503')).toMatch(/unavailable/i)
  })

  it('gives distinct, human copy for the main auth codes', () => {
    expect(errorCopy('invalid_credentials')).toMatch(/email or password/i)
    expect(errorCopy('rate_limited')).toMatch(/too many/i)
  })
})
