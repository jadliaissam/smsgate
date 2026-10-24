import { useState } from 'react'
import { useRouter, type ErrorComponentProps } from '@tanstack/react-router'
import { isApiError } from '../api/client'
import { errorCopy } from '../api/errors'
import { Button } from '../ui/Button'
import { AlertIcon } from '../ui/icons'
import { Spinner } from '../ui/Spinner'
import { AuthLayout } from './AuthLayout'
import { LogoMark } from './Logo'

/** Full-page placeholder while the session is checked on a slow network. */
export function SplashScreen() {
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-5 bg-canvas">
      <LogoMark className="size-11 rounded-xl" />
      <Spinner size="md" label="Loading your dashboard" className="text-fg-subtle" />
    </div>
  )
}

/** In-content placeholder while a page chunk loads. */
export function PagePending() {
  return (
    <div className="flex min-h-48 items-center justify-center">
      <Spinner size="md" className="text-fg-subtle" />
    </div>
  )
}

/** Error card with Retry: re-runs the failed loaders and re-renders. */
export function ErrorPanel({ error, reset }: ErrorComponentProps) {
  const router = useRouter()
  const [retrying, setRetrying] = useState(false)
  const message = isApiError(error) ? errorCopy(error.code) : errorCopy('default')

  const retry = async () => {
    setRetrying(true)
    try {
      await router.invalidate()
      reset()
    } finally {
      setRetrying(false)
    }
  }

  return (
    <div role="alert" className="flex flex-col items-center px-4 py-12 text-center">
      <span className="mb-4 flex size-11 items-center justify-center rounded-2xl bg-danger-50 text-xl text-danger-600 dark:bg-danger-950/50 dark:text-danger-400">
        <AlertIcon />
      </span>
      <h2 className="text-[15px] font-semibold tracking-tight text-fg">Something went wrong</h2>
      <p className="mt-1.5 max-w-sm text-sm text-pretty text-fg-muted">{message}</p>
      <Button variant="secondary" className="mt-6" loading={retrying} onClick={retry}>
        Retry
      </Button>
    </div>
  )
}

/** The session could not be checked (network, 5xx): full-page retry. */
export function SessionError(props: ErrorComponentProps) {
  return (
    <AuthLayout>
      <ErrorPanel {...props} />
    </AuthLayout>
  )
}
