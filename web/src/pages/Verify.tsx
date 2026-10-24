import { getRouteApi, Link } from '@tanstack/react-router'
import { isApiError } from '../api/client'
import { errorCopy } from '../api/errors'
import { useMe, useVerify } from '../api/queries'
import { AuthHeader } from '../layout/AuthLayout'
import { ResendButton } from '../layout/ResendButton'
import { Button } from '../ui/Button'
import { buttonStyles, cx } from '../ui/cx'
import { AlertIcon } from '../ui/icons'
import { Spinner } from '../ui/Spinner'

const route = getRouteApi('/_auth/verify')

export function Verify() {
  const { token = '' } = route.useSearch()
  const verify = useVerify(token)

  if (!token) return <VerifyFailed code="invalid_token" />
  if (verify.isError) {
    const code = isApiError(verify.error) ? verify.error.code : 'default'
    return <VerifyFailed code={code} onRetry={code === 'invalid_token' ? undefined : () => void verify.refetch()} />
  }
  if (verify.isSuccess) return <Verified />

  return (
    <div className="flex flex-col items-center py-8 text-center">
      <Spinner size="lg" label="Verifying your email" className="text-brand-600 dark:text-brand-400" />
      <p className="mt-4 text-sm text-fg-muted">Verifying your email…</p>
    </div>
  )
}

function Verified() {
  return (
    <div className="text-center">
      <AuthHeader
        icon={<SuccessCheck />}
        title="Email verified"
        description="You’re all set. You can now create API keys and send messages."
      />
      <Link to="/" className={cx(buttonStyles('primary'), 'w-full')}>
        Continue
      </Link>
    </div>
  )
}

function VerifyFailed({ code, onRetry }: { code: string; onRetry?: () => void }) {
  const me = useMe()

  let action
  if (onRetry) {
    action = (
      <Button className="w-full" onClick={onRetry}>
        Try again
      </Button>
    )
  } else if (me.isPending) {
    action = <Spinner className="mx-auto text-fg-subtle" />
  } else if (me.data?.email_verified) {
    action = (
      <Link to="/" className={cx(buttonStyles('primary'), 'w-full')}>
        Your email is already verified — continue
      </Link>
    )
  } else if (me.data) {
    action = <ResendButton label="Send a new link" variant="primary" size="md" className="w-full" />
  } else {
    action = (
      <Link to="/login" search={{ next: '/' }} className={cx(buttonStyles('primary'), 'w-full')}>
        Sign in to get a new link
      </Link>
    )
  }

  return (
    <div className="text-center">
      <AuthHeader
        icon={
          <span className="flex size-14 items-center justify-center rounded-2xl bg-danger-50 text-3xl text-danger-600 animate-pop-in motion-reduce:animate-none dark:bg-danger-500/10 dark:text-danger-400">
            <AlertIcon />
          </span>
        }
        title="We couldn’t verify your email"
        description={errorCopy(code)}
      />
      <div className="flex justify-center">{action}</div>
    </div>
  )
}

/** Circle that scales in, then a check that draws itself. */
function SuccessCheck() {
  return (
    <span className="relative inline-flex">
      <span aria-hidden="true" className="absolute inset-0 rounded-full bg-success-500/30 blur-xl" />
      <svg
        viewBox="0 0 56 56"
        className="relative size-16 text-success-600 animate-pop-in motion-reduce:animate-none dark:text-success-400"
        aria-hidden="true"
      >
        <circle cx="28" cy="28" r="26" className="fill-success-50 dark:fill-success-500/15" />
        <circle cx="28" cy="28" r="26" fill="none" stroke="currentColor" strokeOpacity="0.25" strokeWidth="2" />
        <path
          d="m18 28.5 6.5 6.5L38 21.5"
          fill="none"
          stroke="currentColor"
          strokeWidth="3.5"
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeDasharray="24"
          pathLength="24"
          className="animate-check [animation-delay:120ms] motion-reduce:animate-none"
        />
      </svg>
    </span>
  )
}
