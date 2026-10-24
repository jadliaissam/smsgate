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

/**
 * Where to go after signing in: only a URL on our own origin, returned as a
 * path. Parsing with the URL parser (not prefix checks) catches every form
 * browsers treat as off-site: `//evil`, `/\evil`, `/<tab>/evil`, `https://evil`.
 */
export function safeNext(next: string | undefined, origin: string = window.location.origin): string {
  if (!next) return '/'
  let url: URL
  try {
    url = new URL(next, origin)
  } catch {
    return '/'
  }
  if (url.origin !== origin) return '/'
  const path = url.pathname + url.search + url.hash
  // Dot segments can normalise to `//host` (`/.//evil`, `/a/..//evil`),
  // which a later navigation would read as protocol-relative.
  return path.startsWith('//') ? '/' : path
}

/** Inline text link style for auth cards. */
export const textLink =
  'rounded font-medium text-brand-600 hover:text-brand-700 dark:text-brand-400 dark:hover:text-brand-300 ' + focusRing
