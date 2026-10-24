/**
 * Tiny same-origin JSON client. Every call goes to a relative URL (nginx in
 * production and the Vite proxy in dev forward /app, /v1, /device to the Go
 * backend), so the session cookie is first-party and no CORS is involved.
 */

export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

export interface ApiInit {
  method?: string
  body?: unknown
  headers?: Record<string, string>
  signal?: AbortSignal
}

interface ErrorEnvelope {
  error?: { code?: unknown; message?: unknown }
}

function isJSONResponse(res: Response): boolean {
  return /^application\/(?:[\w.+-]+\+)?json\b/i.test(res.headers.get('content-type') ?? '')
}

export async function api<T>(path: string, init: ApiInit = {}): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase()
  const safe = method === 'GET' || method === 'HEAD'
  // Header names are case-insensitive: normalise to lower case so caller
  // headers merge with (and never duplicate) ours.
  const headers: Record<string, string> = { accept: 'application/json' }
  for (const [k, v] of Object.entries(init.headers ?? {})) headers[k.toLowerCase()] = v
  // Backend rule (WR2): every unsafe request carries Content-Type
  // application/json, with or without a body, or it is rejected with 415.
  // This is also what makes the cookie-authenticated API CSRF-safe.
  if (!safe) headers['content-type'] = 'application/json'

  let res: Response
  try {
    res = await fetch(path, {
      method,
      headers,
      body: !safe && init.body !== undefined ? JSON.stringify(init.body) : undefined,
      credentials: 'same-origin',
      signal: init.signal,
    })
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err
    throw new ApiError(0, 'network_error', 'network request failed')
  }

  if (res.status === 204 || res.status === 205) return undefined as T

  if (!res.ok) {
    if (isJSONResponse(res)) {
      const env = (await res.json().catch(() => null)) as ErrorEnvelope | null
      const code = env?.error?.code
      if (typeof code === 'string' && code) {
        const message = typeof env?.error?.message === 'string' ? env.error.message : code
        throw new ApiError(res.status, code, message)
      }
    }
    throw new ApiError(res.status, `http_${res.status}`, res.statusText || `HTTP ${res.status}`)
  }

  const text = await res.text()
  if (!text) return undefined as T
  try {
    return JSON.parse(text) as T
  } catch {
    throw new ApiError(res.status, 'invalid_response', 'response was not valid JSON')
  }
}

export function isApiError(err: unknown, code?: string): err is ApiError {
  return err instanceof ApiError && (code === undefined || err.code === code)
}
