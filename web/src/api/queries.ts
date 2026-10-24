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
import { api, ApiError, isApiError } from './client'
import { errorCopy } from './errors'
import { clearResendCooldown } from './resendCooldown'
import type { ApiKey, CreatedApiKey, Device, Me, Page, PairingCode } from './types'
import { useToast } from '../ui/Toast'

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

// Credentials are mutation variables: gcTime 0 drops the settled mutation
// (and the password in it) from the cache as soon as its observer resets or
// unmounts, instead of keeping it for the default five minutes.
export function useLogin() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (c: Credentials) => api<undefined>('/app/login', { method: 'POST', body: c }),
    gcTime: 0,
    // A new session: forget everything cached for a previous one. This runs
    // on a signed-out page (login/signup), before navigating into the app, so
    // no protected query is mounted to refetch into (or be orphaned by) it.
    onSuccess: () => {
      qc.clear()
      clearResendCooldown()
    },
  })
}

export function useSignup() {
  return useMutation({
    mutationFn: (c: Credentials) => api<{ id: string; email: string }>('/app/signup', { method: 'POST', body: c }),
    gcTime: 0,
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
      clearResendCooldown()
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

interface DevicesOptions {
  /** Poll interval in ms (the pair modal polls while open), or false. */
  refetchInterval?: number | false
}

export function useDevices({ refetchInterval = false }: DevicesOptions = {}) {
  return useQuery({
    queryKey: devicesKey,
    queryFn: ({ signal }) => api<Page<Device>>('/app/devices', { signal }).then((p) => p.data),
    refetchInterval,
  })
}

// ---- devices ---------------------------------------------------------------

export function useCreatePairingCode() {
  return useMutation({
    mutationFn: () => api<PairingCode>('/app/pairing-codes', { method: 'POST' }),
  })
}

/** Snapshot the devices list for an optimistic update; returns the rollback. */
async function optimisticDevices(qc: QueryClient, update: (list: Device[]) => Device[]) {
  await qc.cancelQueries({ queryKey: devicesKey })
  const previous = qc.getQueryData<Device[]>(devicesKey)
  if (previous) qc.setQueryData<Device[]>(devicesKey, update(previous))
  return { previous }
}

function codeOf(err: unknown): string {
  return isApiError(err) ? err.code : 'default'
}

export interface RenameDeviceInput {
  id: string
  name: string
}

/** PATCH /app/devices/{id}: the new name shows at once, rolled back on error. */
export function useRenameDevice() {
  const qc = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: ({ id, name }: RenameDeviceInput) =>
      api<Device>(`/app/devices/${encodeURIComponent(id)}`, { method: 'PATCH', body: { name } }),
    onMutate: ({ id, name }) => optimisticDevices(qc, (list) => list.map((d) => (d.id === id ? { ...d, name } : d))),
    onSuccess: (device) =>
      qc.setQueryData<Device[]>(devicesKey, (list) => list?.map((d) => (d.id === device.id ? device : d))),
    onError: (err, _input, ctx) => {
      if (ctx?.previous) qc.setQueryData(devicesKey, ctx.previous)
      toast.error('Couldn’t rename the device', errorCopy(codeOf(err)))
    },
  })
}

/** DELETE /app/devices/{id}: the card disappears at once, back on error. */
export function useRevokeDevice() {
  const qc = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: (device: Device) => api<undefined>(`/app/devices/${encodeURIComponent(device.id)}`, { method: 'DELETE' }),
    onMutate: (device) => optimisticDevices(qc, (list) => list.filter((d) => d.id !== device.id)),
    onSuccess: (_res, device) => toast.success(`${device.name} revoked`, 'The phone was signed out.'),
    onError: (err, device, ctx) => {
      if (ctx?.previous) qc.setQueryData(devicesKey, ctx.previous)
      toast.error(`Couldn’t revoke ${device.name}`, errorCopy(codeOf(err)))
    },
    onSettled: () => qc.invalidateQueries({ queryKey: devicesKey }),
  })
}

// ---- API keys --------------------------------------------------------------

export function useApiKeys() {
  return useQuery({
    queryKey: apiKeysKey,
    queryFn: ({ signal }) => api<Page<ApiKey>>('/app/api-keys', { signal }).then((p) => p.data),
  })
}

/**
 * POST /app/api-keys. [Review Focus 2] The response is the only place the
 * full key ever appears: it is never written to the query cache (the list is
 * refetched instead, which carries the prefix only), and gcTime 0 drops the
 * settled mutation, response included, once the caller resets it. The caller
 * keeps the key in component state for as long as it is shown.
 */
export function useCreateApiKey() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (name: string) => api<CreatedApiKey>('/app/api-keys', { method: 'POST', body: { name } }),
    gcTime: 0,
    // Not returned: TanStack would await the refetch before the caller's
    // onSuccess, holding back the one-time key until the list reloads.
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: apiKeysKey })
    },
    // The server says the email is not verified: refresh the session so the
    // page switches to its unverified state.
    onError: (err) => {
      if (isApiError(err, 'email_not_verified')) void qc.invalidateQueries({ queryKey: meKey })
    },
  })
}

/** DELETE /app/api-keys/{id}: marked revoked at once, rolled back on error. */
export function useRevokeApiKey() {
  const qc = useQueryClient()
  const toast = useToast()
  return useMutation({
    mutationFn: (key: ApiKey) => api<undefined>(`/app/api-keys/${encodeURIComponent(key.id)}`, { method: 'DELETE' }),
    onMutate: async (key) => {
      await qc.cancelQueries({ queryKey: apiKeysKey })
      const previous = qc.getQueryData<ApiKey[]>(apiKeysKey)
      const revokedAt = new Date().toISOString()
      if (previous) {
        qc.setQueryData<ApiKey[]>(
          apiKeysKey,
          previous.map((k) => (k.id === key.id ? { ...k, revoked_at: revokedAt } : k)),
        )
      }
      return { previous }
    },
    onSuccess: (_res, key) => toast.success(`${key.name} revoked`, 'Requests using this key are now rejected.'),
    onError: (err, key, ctx) => {
      if (ctx?.previous) qc.setQueryData(apiKeysKey, ctx.previous)
      toast.error(`Couldn’t revoke ${key.name}`, errorCopy(codeOf(err)))
    },
    onSettled: () => qc.invalidateQueries({ queryKey: apiKeysKey }),
  })
}
