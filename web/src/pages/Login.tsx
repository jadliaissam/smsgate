import { useState, type FormEvent } from 'react'
import { getRouteApi, Link, useNavigate } from '@tanstack/react-router'
import { useLogin } from '../api/queries'
import { AuthHeader } from '../layout/AuthLayout'
import { FormAlert } from '../layout/FormAlert'
import { Button } from '../ui/Button'
import { Input } from '../ui/Input'
import { useToast } from '../ui/Toast'
import { authFormErrors, safeNext, textLink, type AuthFormErrors } from './authForm'

const route = getRouteApi('/_auth/login')

export function Login() {
  const { next } = route.useSearch()
  const navigate = useNavigate()
  const login = useLogin()
  const toast = useToast()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [errors, setErrors] = useState<AuthFormErrors>({})

  const submit = (e: FormEvent) => {
    e.preventDefault()
    setErrors({})
    login.mutate(
      { email: email.trim(), password },
      {
        onSuccess: () => void navigate({ href: safeNext(next), replace: true }),
        onError: (err) => {
          const mapped = authFormErrors(err)
          if (mapped.toast) toast.error(mapped.toast)
          setErrors(mapped)
        },
      },
    )
  }

  return (
    <>
      <AuthHeader title="Welcome back" description="Sign in to manage your phones and API keys." />
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
          autoComplete="current-password"
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          error={errors.password}
        />
        {errors.form && <FormAlert>{errors.form}</FormAlert>}
        <Button type="submit" loading={login.isPending} className="mt-1 w-full">
          Sign in
        </Button>
      </form>
      <p className="mt-6 text-center text-sm text-fg-muted">
        New to SMSGate?{' '}
        <Link
          to="/signup"
          className={textLink}
        >
          Create an account
        </Link>
      </p>
    </>
  )
}
