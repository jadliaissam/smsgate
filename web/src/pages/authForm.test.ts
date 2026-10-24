import { describe, expect, it } from 'vitest'
import { safeNext } from './authForm'

const origin = 'http://localhost:3000'

describe('safeNext', () => {
  it.each([
    ['/devices', '/devices'],
    ['/api-keys?tab=active#top', '/api-keys?tab=active#top'],
    // Percent-encoded tab stays a (harmless) same-origin path.
    ['/%09/evil.com', '/%09/evil.com'],
  ])('keeps same-origin %s', (next, want) => {
    expect(safeNext(next, origin)).toBe(want)
  })

  it.each([
    ['protocol-relative', '//evil.com'],
    ['backslash', '/\\evil.com'],
    ['tab (stripped by the URL parser)', '/\t/evil.com'],
    ['newline', '/\n/evil.com'],
    ['absolute', 'https://evil.com'],
    ['absolute path on another origin', 'https://evil.com/devices'],
    ['javascript URL', 'javascript:alert(1)'],
  ])('rejects %s', (_label, next) => {
    expect(safeNext(next, origin)).toBe('/')
  })

  it('falls back home when next is missing', () => {
    expect(safeNext(undefined, origin)).toBe('/')
    expect(safeNext('', origin)).toBe('/')
  })

  it('normalises an absolute URL on our own origin to its path', () => {
    expect(safeNext(`${origin}/devices?x=1`, origin)).toBe('/devices?x=1')
  })

  it('defaults to the page origin', () => {
    expect(safeNext('/devices')).toBe('/devices')
    expect(safeNext('//evil.com')).toBe('/')
  })
})
