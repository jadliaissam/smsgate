import { useState, type FormEvent } from 'react'
import { Link } from '@tanstack/react-router'
import { useLogin, useSignup } from '../api/queries'
import { AuthHeader } from '../layout/AuthLayout'
import { FormAlert } from '../layout/FormAlert'
import { ResendButton } from '../layout/ResendButton'
import { Button } from '../ui/Button'
import { buttonStyles, cx } from '../ui/cx'
import { Input } from '../ui/Input'
import { useToast } from '../ui/Toast'
import { authFormErrors, textLink, type AuthFormErrors } from './authForm'

export function Signup() {
  const signup = useSignup()
  const login = useLogin()
  const toast = useToast()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [errors, setErrors] = useState<AuthFormErrors>({})
  const [sentTo, setSentTo] = useState<string | null>(null)

  const submit = (e: FormEvent) => {
    e.preventDefault()
    setErrors({})
    const creds = { email: email.trim(), password }
    signup.mutate(creds, {
      onSuccess: (user) => {
        // Signup does not open a session; sign in right away so the
        // dashboard (and resending the link) work without a second form.
        login.mutate(creds, { onSettled: () => setSentTo(user.email) })
      },
      onError: (err) => {
        const mapped = authFormErrors(err)
        if (mapped.toast) toast.error(mapped.toast)
        setErrors(mapped)
      },
    })
  }

  if (sentTo) return <CheckInbox email={sentTo} signedIn={login.isSuccess} />

  return (
    <>
      <AuthHeader title="Create your account" description="Pair a phone and send your first SMS in minutes." />
      <form onSubmit={submit} className="flex flex-col gap-4">
        <Input
          label="Email"
          type="email"
          name="email"
          autoComplete="email"
          required
          autoFocus
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          error={errors.email}
        />
        <Input
          label="Password"
          type="password"
          name="password"
          autoComplete="new-password"
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          error={errors.password}
          hint={errors.password ? undefined : 'At least 10 characters. A passphrase works well.'}
        />
        {errors.form && <FormAlert>{errors.form}</FormAlert>}
        <Button type="submit" loading={signup.isPending || login.isPending} className="mt-1 w-full">
          Create account
        </Button>
      </form>
      <p className="mt-6 text-center text-sm text-fg-muted">
        Already have an account?{' '}
        <Link to="/login" className={textLink}>
          Sign in
        </Link>
      </p>
    </>
  )
}

function CheckInbox({ email, signedIn }: { email: string; signedIn: boolean }) {
  return (
    <div className="text-center">
      <AuthHeader
        icon={<MailBadge />}
        title="Check your inbox"
        description={
          <>
            We sent a verification link to <strong className="font-medium text-fg">{email}</strong>. Click it to
            unlock API keys.
          </>
        }
      />
      <div className="flex flex-col gap-3">
        {signedIn ? (
          <>
            <Link to="/" className={cx(buttonStyles('primary'), 'w-full')}>
              Continue to dashboard
            </Link>
            <div className="flex items-center justify-center gap-2 text-sm text-fg-muted">
              <span>Didn’t get it?</span>
              <ResendButton variant="ghost" coolingDown />
            </div>
          </>
        ) : (
          <Link to="/login" className={cx(buttonStyles('primary'), 'w-full')}>
            Sign in
          </Link>
        )}
      </div>
    </div>
  )
}

function MailBadge() {
  return (
    <span className="relative inline-flex">
      <span aria-hidden="true" className="absolute inset-0 rounded-2xl bg-brand-500/25 blur-xl" />
      <span className="relative flex size-14 items-center justify-center rounded-2xl border border-border bg-surface-raised text-brand-600 shadow-raised animate-pop-in motion-reduce:animate-none dark:text-brand-400">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} className="size-7" aria-hidden="true">
          <rect x="3" y="5" width="18" height="14" rx="3" />
          <path d="m4 7.5 8 5.5 8-5.5" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </span>
    </span>
  )
}
