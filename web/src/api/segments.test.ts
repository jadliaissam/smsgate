import { describe, expect, it } from 'vitest'
import { normalizeNumber, segments, validE164 } from './segments'

const r = (s: string, n: number) => s.repeat(n)

// Same table as backend/internal/sms/sms_test.go (TestSegments).
describe('segments', () => {
  it.each([
    ['empty', '', 0, false],
    ['160a', r('a', 160), 1, false],
    ['161a', r('a', 161), 2, false],
    ['306a', r('a', 306), 2, false],
    ['307a', r('a', 307), 3, false],
    ['459a', r('a', 459), 3, false],
    ['460a', r('a', 460), 4, false],
    ['80euro', r('€', 80), 1, false],
    ['81euro', r('€', 81), 2, false],
    ['ext chars', '^{}\\[~]|', 1, false],
    ['form feed', '\f', 1, false],
    ['gsm non-ascii', '@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ¤¡ÄÖÑÜ§¿äöñüà', 1, false],
    ['backtick is ucs2', '`', 1, true],
    ['70ą', r('ą', 70), 1, true],
    ['71ą', r('ą', 71), 2, true],
    ['134ą', r('ą', 134), 2, true],
    ['135ą', r('ą', 135), 3, true],
    ['35emoji', r('😀', 35), 1, true],
    ['36emoji', r('😀', 36), 2, true],
    ['101emoji', r('😀', 101), 4, true],
    ['mixed', r('a', 100) + 'ą', 2, true],
    ['control char is ucs2', '\x01', 1, true],
    // An ESC pair or a surrogate pair never straddles a part boundary.
    ['ext straddles 153', r('a', 152) + '{' + r('a', 152), 3, false],
    ['ext ends part', r('a', 151) + '{' + r('a', 153), 2, false],
    ['euro straddles 153', r('a', 152) + '€' + r('a', 152), 3, false],
    ['single part ext at 160', r('a', 158) + '{', 1, false],
    ['emoji straddles 67', r('ж', 66) + '😀' + r('ж', 66), 3, true],
    ['emoji ends part', r('ж', 65) + '😀' + r('ж', 67), 2, true],
    ['single part emoji at 70', r('ж', 68) + '😀', 1, true],
  ])('%s', (_name, body, seg, ucs2) => {
    expect(segments(body)).toEqual({ segments: seg, ucs2 })
  })
})

// Same table as TestValidE164.
describe('validE164', () => {
  it.each([
    ['+37061234567', true],
    ['+1234567', true],
    ['+123456789012345', true],
    ['37061234567', false],
    ['+0123456789', false],
    ['+1234567890123456', false],
    ['+123456', false],
    ['+12 345', false],
    ['+1234567a', false],
    ['+', false],
    ['', false],
    ['+37061234567\n', false],
  ])('%j → %s', (input, want) => {
    expect(validE164(input)).toBe(want)
  })
})

describe('normalizeNumber', () => {
  it('drops spaces, dashes, dots and parentheses people type', () => {
    expect(normalizeNumber(' +370 (612) 34-56.7 ')).toBe('+37061234567')
    expect(normalizeNumber('+1234567a')).toBe('+1234567a')
  })
})
