import { focusRing } from '../ui/cx'
import { isApiError } from '../api/client'
import { errorCopy } from '../api/errors'

export interface AuthFormErrors {
  email?: string
  password?: string
  /** Shown above the submit button. */
  form?: string
  /** Transient failures (rate limit, busy, network) go to a toast instead. */
  toast?: string
}

const emailCodes = new Set(['invalid_email', 'email_taken'])
const passwordCodes = new Set(['weak_password'])
const formCodes = new Set(['invalid_credentials', 'email_not_verified'])

/** Routes a signup/login failure to the field it belongs to. */
export function authFormErrors(err: unknown): AuthFormErrors {
  const code = isApiError(err) ? err.code : 'default'
  const copy = errorCopy(code)
  if (emailCodes.has(code)) return { email: copy }
  if (passwordCodes.has(code)) return { password: copy }
  if (formCodes.has(code)) return { form: copy }
  return { toast: copy }
}

/** Only same-origin paths: `//evil.example` or `/\evil` would leave the site. */
export function safeNext(next: string | undefined): string {
  if (!next || !next.startsWith('/') || next.startsWith('//') || next.startsWith('/\\')) return '/'
  return next
}

/** Inline text link style for auth cards. */
export const textLink =
  'rounded font-medium text-brand-600 hover:text-brand-700 dark:text-brand-400 dark:hover:text-brand-300 ' + focusRing
