import {
  MutationCache,
  QueryCache,
  QueryClient,
  queryOptions,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { useRouter } from '@tanstack/react-router'
import { api, ApiError } from './client'
import type { ApiKey, Device, Me, Page } from './types'

export const meKey = ['me'] as const

export const meQuery = queryOptions({
  queryKey: meKey,
  queryFn: ({ signal }) => api<Me>('/app/me', { signal }),
  retry: false,
})

/** True for the backend's "no or expired session" answer (not bad credentials). */
export function isUnauthorized(err: unknown): boolean {
  return err instanceof ApiError && err.status === 401 && err.code === 'unauthorized'
}

interface QueryClientOptions {
  /** Called on any 401 `unauthorized` from a query or mutation. */
  onUnauthorized: () => void
  /** Retry failed queries (off in tests). */
  retry?: boolean
}

/**
 * The app's QueryClient. 4xx answers are final (retrying cannot fix them);
 * transient failures (network, 5xx) are retried once.
 */
export function createQueryClient({ onUnauthorized, retry = true }: QueryClientOptions): QueryClient {
  const onError = (err: unknown) => {
    if (isUnauthorized(err)) onUnauthorized()
  }
  return new QueryClient({
    queryCache: new QueryCache({ onError }),
    mutationCache: new MutationCache({ onError }),
    defaultOptions: {
      queries: {
        staleTime: 15_000,
        retry: retry
          ? (count, err) => count < 1 && !(err instanceof ApiError && err.status >= 400 && err.status < 500)
          : false,
      },
      mutations: { retry: false },
    },
  })
}

// ---- session -------------------------------------------------------------

export function useMe() {
  return useQuery(meQuery)
}

export interface Credentials {
  email: string
  password: string
}

export function useLogin() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (c: Credentials) => api<undefined>('/app/login', { method: 'POST', body: c }),
    // A new session: forget anything cached for a previous one.
    onSuccess: () => qc.removeQueries({ queryKey: meKey }),
  })
}

export function useSignup() {
  return useMutation({
    mutationFn: (c: Credentials) => api<{ id: string; email: string }>('/app/signup', { method: 'POST', body: c }),
  })
}

/**
 * Signs out, then goes to /login. Signed out either way: the cache is cleared
 * even if the request failed, but only after leaving the protected pages so
 * their queries do not refetch into the emptied cache.
 */
export function useLogout() {
  const qc = useQueryClient()
  const router = useRouter()
  return useMutation({
    mutationFn: () => api<undefined>('/app/logout', { method: 'POST' }),
    onSettled: async () => {
      await router.navigate({ to: '/login' })
      qc.clear()
    },
  })
}

/**
 * Consumes a verification token. A query (not a mutation) so the single
 * request is shared by StrictMode's double mount and never repeated: the
 * token is one-shot.
 */
export function useVerify(token: string) {
  const qc = useQueryClient()
  return useQuery({
    queryKey: ['verify', token],
    queryFn: async () => {
      await api<undefined>('/app/verify', { method: 'POST', body: { token } })
      void qc.invalidateQueries({ queryKey: meKey })
      return true
    },
    enabled: token !== '',
    retry: false,
    staleTime: Infinity,
    gcTime: Infinity,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
}

export function useResendVerification() {
  return useMutation({
    mutationFn: () => api<undefined>('/app/resend-verification', { method: 'POST' }),
  })
}

// ---- lists (minimal; W5/W6 extend these with mutations) -----------------

export const devicesKey = ['devices'] as const
export const apiKeysKey = ['api-keys'] as const

export function useDevices() {
  return useQuery({
    queryKey: devicesKey,
    queryFn: ({ signal }) => api<Page<Device>>('/app/devices', { signal }).then((p) => p.data),
  })
}

export function useApiKeys() {
  return useQuery({
    queryKey: apiKeysKey,
    queryFn: ({ signal }) => api<Page<ApiKey>>('/app/api-keys', { signal }).then((p) => p.data),
  })
}
