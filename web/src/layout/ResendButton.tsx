import { isApiError } from '../api/client'
import { errorCopy } from '../api/errors'
import { useResendVerification } from '../api/queries'
import { Button, type ButtonProps } from '../ui/Button'
import { useToast } from '../ui/Toast'
import { startResendCooldown, useResendCooldown } from '../api/resendCooldown'

interface ResendButtonProps extends Pick<ButtonProps, 'variant' | 'size' | 'className'> {
  label?: string
}

/**
 * Re-sends the verification email (POST /app/resend-verification), then
 * counts down 60 s before it can be used again. The countdown is shared by
 * every instance and persisted for the tab (see startResendCooldown).
 */
export function ResendButton({ label = 'Resend email', ...buttonProps }: ResendButtonProps) {
  const resend = useResendVerification()
  const toast = useToast()
  const left = useResendCooldown()

  const onClick = () =>
    resend.mutate(undefined, {
      onSuccess: () => {
        startResendCooldown()
        toast.success('Verification email sent', 'Check your inbox for the new link.')
      },
      onError: (err) => {
        // Rate limited: the server will refuse for a while anyway.
        if (isApiError(err, 'rate_limited')) startResendCooldown()
        toast.error(errorCopy(isApiError(err) ? err.code : 'default'))
      },
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
