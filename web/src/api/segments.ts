/**
 * Port of backend/internal/sms: E.164 validation and SMS segment counting,
 * so the send form can show what the server will accept before submitting.
 * Keep in sync with sms.go (the tests share its table).
 */

/** GSM 03.38 septet cost per ASCII code: 0 = not GSM-7, 1 = basic, 2 = extension (ESC + char). */
const asciiCost = (() => {
  const t = new Uint8Array(128)
  const basic = '@$\n\r !"#%&\'()*+,-./0123456789:;<=>?ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz'
  for (const c of basic) t[c.charCodeAt(0)] = 1
  for (const c of '\f^{}\\[~]|') t[c.charCodeAt(0)] = 2
  return t
})()

const basicNonAscii = new Set('£¥èéùìòÇØøÅåΔΦΓΛΩΠΨΣΘΞÆæßÉ¤¡ÄÖÑÜ§¿äöñüà')

/** Septet cost of one code point, or 0 if it is not in GSM 03.38. */
function gsmCost(ch: string, cp: number): number {
  if (cp < 128) return asciiCost[cp]!
  if (ch === '€') return 2
  return basicNonAscii.has(ch) ? 1 : 0
}

export const GSM_SINGLE = 160
export const GSM_PART = 153
export const UCS_SINGLE = 70
export const UCS_PART = 67
/** The backend's MAX_SEGMENTS default. */
export const MAX_SEGMENTS = 3

export interface SegmentCount {
  segments: number
  /** True when the body needs UCS-2 (any character outside GSM-7). */
  ucs2: boolean
}

/**
 * Number of SMS segments `body` needs. GSM-7: 160 single / 153 per part;
 * UCS-2 (UTF-16 code units): 70 single / 67 per part. Concatenated parts are
 * filled character by character, so an ESC pair or a surrogate pair that
 * does not fit the current part starts the next one. Empty → 0 segments.
 */
export function segments(body: string): SegmentCount {
  if (body === '') return { segments: 0, ucs2: false }
  let septets = 0
  let units = 0
  let gsmParts = 1
  let gsmFill = 0
  let ucsParts = 1
  let ucsFill = 0
  let gsm = true
  for (const ch of body) {
    const cp = ch.codePointAt(0)!
    if (gsm) {
      const c = gsmCost(ch, cp)
      if (c !== 0) {
        septets += c
        if (gsmFill + c > GSM_PART) {
          gsmParts++
          gsmFill = 0
        }
        gsmFill += c
      } else {
        gsm = false
      }
    }
    const u = cp > 0xffff ? 2 : 1
    units += u
    if (ucsFill + u > UCS_PART) {
      ucsParts++
      ucsFill = 0
    }
    ucsFill += u
  }
  if (gsm) return { segments: septets <= GSM_SINGLE ? 1 : gsmParts, ucs2: false }
  return { segments: units <= UCS_SINGLE ? 1 : ucsParts, ucs2: true }
}

/** Matches ^\+[1-9][0-9]{6,14}$, like the backend's sms.ValidE164. */
export function validE164(s: string): boolean {
  return /^\+[1-9][0-9]{6,14}$/.test(s)
}

/** Strips the separators people type in numbers: spaces, dashes, dots, parentheses. */
export function normalizeNumber(s: string): string {
  return s.replace(/[\s().-]/g, '')
}
