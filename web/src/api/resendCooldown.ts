import { useEffect, useState } from 'react'

/** Matches the backend's RESEND_VERIFICATION_INTERVAL default. */
export const RESEND_COOLDOWN_S = 60

/** sessionStorage key holding the cooldown end (epoch ms). */
export const RESEND_COOLDOWN_KEY = 'smsgate.resendCooldownEndsAt'

// One cooldown for every ResendButton (banner, overview step, check-inbox),
// persisted per tab so it survives remounts and reloads. Memory is the
// fallback when storage is unavailable (private mode, blocked site data).
let memoryEndsAt = 0
const listeners = new Set<() => void>()

function readEndsAt(): number {
  try {
    const raw = sessionStorage.getItem(RESEND_COOLDOWN_KEY)
    const n = raw === null ? 0 : Number(raw)
    return Number.isFinite(n) ? n : 0
  } catch {
    return memoryEndsAt
  }
}

/** Starts the shared resend cooldown (a verification email was just sent). */
export function startResendCooldown(seconds = RESEND_COOLDOWN_S) {
  memoryEndsAt = Date.now() + seconds * 1000
  try {
    sessionStorage.setItem(RESEND_COOLDOWN_KEY, String(memoryEndsAt))
  } catch {
    // storage unavailable: the in-memory value still syncs this tab
  }
  for (const l of listeners) l()
}

/** Ends the cooldown (sign-in or sign-out: a different session starts). */
export function clearResendCooldown() {
  memoryEndsAt = 0
  try {
    sessionStorage.removeItem(RESEND_COOLDOWN_KEY)
  } catch {
    // storage unavailable: memory was reset
  }
  for (const l of listeners) l()
}

/** Seconds left on the shared cooldown (0 when it can be used), ticking each second. */
export function useResendCooldown(): number {
  const [endsAt, setEndsAt] = useState(readEndsAt)
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    const onChange = () => {
      setEndsAt(readEndsAt())
      setNow(Date.now())
    }
    listeners.add(onChange)
    return () => {
      listeners.delete(onChange)
    }
  }, [])

  const active = endsAt > now
  useEffect(() => {
    if (!active) return
    const t = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(t)
  }, [active])

  return active ? Math.ceil((endsAt - now) / 1000) : 0
}
