import { vi, type Mock } from 'vitest'

/** One canned response. `json` is serialised; `text` is sent verbatim. */
export interface MockRoute {
  method?: string
  /** Exact path (query string included if given) or a RegExp tested against path+query. */
  path: string | RegExp
  status?: number
  json?: unknown
  text?: string
  headers?: Record<string, string>
  /** Reject the fetch (simulates a network failure). */
  networkError?: boolean
  /** Serve at most once, then fall through to later routes. */
  once?: boolean
  /** Hold the response until this settles (observe loading states). */
  until?: Promise<unknown>
}

export interface RecordedCall {
  method: string
  path: string
  headers: Record<string, string>
  body: unknown
  credentials: RequestCredentials | undefined
}

export interface FetchMock {
  fn: Mock<typeof fetch>
  calls: RecordedCall[]
  /** Calls whose method and path match. */
  callsTo(method: string, path: string | RegExp): RecordedCall[]
  /** Add routes at the front (they win over existing ones). */
  use(...routes: MockRoute[]): void
}

function pathOf(input: RequestInfo | URL): string {
  const raw = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
  const url = new URL(raw, 'http://localhost')
  return url.pathname + url.search
}

function matches(route: MockRoute, method: string, path: string): boolean {
  if ((route.method ?? 'GET').toUpperCase() !== method) return false
  return typeof route.path === 'string' ? route.path === path : route.path.test(path)
}

/**
 * Replaces global fetch with a router over canned responses. Unmatched
 * requests fail the test loudly instead of hanging.
 */
export function mockFetch(routes: MockRoute[] = []): FetchMock {
  const table = [...routes]
  const calls: RecordedCall[] = []

  const fn = vi.fn<typeof fetch>(async (input, init) => {
    const method = (init?.method ?? 'GET').toUpperCase()
    const path = pathOf(input)
    const headers: Record<string, string> = {}
    new Headers(init?.headers).forEach((v, k) => (headers[k] = v))
    let body: unknown = init?.body ?? undefined
    if (typeof body === 'string') {
      try {
        body = JSON.parse(body)
      } catch {
        // leave non-JSON bodies as-is
      }
    }
    calls.push({ method, path, headers, body, credentials: init?.credentials })

    const i = table.findIndex((r) => matches(r, method, path))
    if (i < 0) throw new Error(`mockFetch: no route for ${method} ${path}`)
    const route = table[i]!
    if (route.once) table.splice(i, 1)
    if (route.until) await route.until.catch(() => undefined)
    if (route.networkError) throw new TypeError('Failed to fetch')

    const status = route.status ?? 200
    const resHeaders = new Headers(route.headers)
    let payload: string | null = null
    if (route.json !== undefined) {
      payload = JSON.stringify(route.json)
      if (!resHeaders.has('content-type')) resHeaders.set('content-type', 'application/json; charset=utf-8')
    } else if (route.text !== undefined) {
      payload = route.text
    }
    return new Response(status === 204 ? null : payload, { status, headers: resHeaders })
  })

  vi.stubGlobal('fetch', fn)

  return {
    fn,
    calls,
    callsTo: (method, path) =>
      calls.filter(
        (c) => c.method === method.toUpperCase() && (typeof path === 'string' ? c.path === path : path.test(c.path)),
      ),
    use: (...more) => table.unshift(...more),
  }
}

/** Convenience for the backend error envelope. */
export function apiError(status: number, code: string, message = code): Pick<MockRoute, 'status' | 'json'> {
  return { status, json: { error: { code, message } } }
}
