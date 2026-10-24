import { useEffect, useState } from 'react'
import { isApiError } from '../api/client'
import { errorCopy } from '../api/errors'
import { useResendVerification } from '../api/queries'
import { Button, type ButtonProps } from '../ui/Button'
import { useToast } from '../ui/Toast'

/** Matches the backend's RESEND_VERIFICATION_INTERVAL default. */
export const RESEND_COOLDOWN_S = 60

/** Seconds left on a countdown; `start()` restarts it at `seconds`. */
function useCooldown(seconds: number, startActive: boolean) {
  const [endsAt, setEndsAt] = useState<number | null>(() => (startActive ? Date.now() + seconds * 1000 : null))
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    if (endsAt === null) return
    const t = window.setInterval(() => {
      const n = Date.now()
      setNow(n)
      if (n >= endsAt) setEndsAt(null)
    }, 1000)
    return () => window.clearInterval(t)
  }, [endsAt])

  const left = endsAt === null ? 0 : Math.max(0, Math.ceil((endsAt - now) / 1000))
  const start = () => {
    const n = Date.now()
    setNow(n)
    setEndsAt(n + seconds * 1000)
  }
  return { left, start }
}

interface ResendButtonProps extends Pick<ButtonProps, 'variant' | 'size' | 'className'> {
  label?: string
  /** Start on cooldown (a link was just sent, e.g. right after signup). */
  coolingDown?: boolean
}

/**
 * Re-sends the verification email (POST /app/resend-verification), then
 * counts down 60 s before it can be used again.
 */
export function ResendButton({ label = 'Resend email', coolingDown = false, ...buttonProps }: ResendButtonProps) {
  const resend = useResendVerification()
  const toast = useToast()
  const { left, start } = useCooldown(RESEND_COOLDOWN_S, coolingDown)

  const onClick = () =>
    resend.mutate(undefined, {
      onSuccess: () => {
        start()
        toast.success('Verification email sent', 'Check your inbox for the new link.')
      },
      onError: (err) => toast.error(errorCopy(isApiError(err) ? err.code : 'default')),
    })

  return (
    <Button
      variant="secondary"
      size="sm"
      {...buttonProps}
      loading={resend.isPending}
      disabled={left > 0}
      onClick={onClick}
    >
      {left > 0 ? `Resend in ${left}s` : label}
    </Button>
  )
}
