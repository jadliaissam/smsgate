import {
  keepPreviousData,
  MutationCache,
  QueryCache,
  QueryClient,
  queryOptions,
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
  type InfiniteData,
} from '@tanstack/react-query'
import { useRouter } from '@tanstack/react-router'
import { api, ApiError, apiResponse, isApiError } from './client'
import { errorCopy } from './errors'
import { clearResendCooldown } from './resendCooldown'
import {
  type ApiKey,
  type CreatedApiKey,
  type Device,
  type IssuedPairingCode,
  type Me,
  type Message,
  type MessageStatus,
  type Page,
  type PairingCode,
  type SendMessageInput,
  type Stats,
} from './types'
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
  /** Fetch on mount even if the cached list is fresh (pair modal baseline). */
  refetchOnMount?: boolean | 'always'
}

export function useDevices({ refetchInterval = false, refetchOnMount = true }: DevicesOptions = {}) {
  return useQuery({
    queryKey: devicesKey,
    queryFn: ({ signal }) => api<Page<Device>>('/app/devices', { signal }).then((p) => p.data),
    refetchInterval,
    refetchOnMount,
  })
}

// ---- devices ---------------------------------------------------------------

/**
 * POST /app/pairing-codes. The lifetime is measured against the server's
 * clock (Date header) so a skewed browser clock does not shorten or stretch
 * the countdown; without the header the browser clock is used.
 */
export function useCreatePairingCode() {
  return useMutation({
    mutationFn: async (): Promise<IssuedPairingCode> => {
      const { data, serverDate } = await apiResponse<PairingCode>('/app/pairing-codes', { method: 'POST' })
      const receivedAt = Date.now()
      return { ...data, receivedAt, ttlMs: new Date(data.expires_at).getTime() - (serverDate ?? receivedAt) }
    },
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
    onSettled: () => qc.invalidateQueries({ queryKey: devicesKey }),
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

// ---- messages --------------------------------------------------------------

export const messagesKey = ['messages'] as const

export interface MessageFilters {
  status?: MessageStatus
  deviceId?: string
}

/** Page size of the messages log (the backend default). */
export const MESSAGES_PAGE_SIZE = 50
/** Poll interval while any listed message can still change status. */
export const MESSAGES_POLL_MS = 5000

/** GET /app/messages URL; `before` is the previous page's `next_before`. */
export function messagesPath(filters: MessageFilters, limit: number, before?: string): string {
  const q = new URLSearchParams({ limit: String(limit) })
  if (filters.status) q.set('status', filters.status)
  if (filters.deviceId) q.set('device_id', filters.deviceId)
  if (before) q.set('before', before)
  return `/app/messages?${q}`
}

type MessagesData = InfiniteData<Page<Message>, string | undefined>

interface MessagesListKey {
  status?: MessageStatus
  deviceId?: string
  limit: number
}

function listKey(k: MessagesListKey) {
  return [...messagesKey, 'list', k] as const
}

/**
 * How long after its `expires_at` a dispatched message can still change: the
 * backend marks a phone that never reported back `device_lost` 15 min after
 * expiry (DEVICE_LOST_AFTER); 5 min of slack covers the sweeper interval.
 */
export const DISPATCHED_PENDING_MS = 20 * 60_000
/**
 * How long after `sent_at` a sent message is watched for a delivery report.
 * Some carriers never send one, so a sent message is not polled forever.
 */
export const SENT_PENDING_MS = 15 * 60_000

/**
 * True while a message can still change status on its own: queued (the
 * backend expires it), dispatched until shortly after the device-lost
 * deadline, sent for a while awaiting a delivery report.
 */
export function isPending(m: Message, now = Date.now()): boolean {
  switch (m.status) {
    case 'queued':
      return true
    case 'dispatched':
      return new Date(m.expires_at).getTime() + DISPATCHED_PENDING_MS > now
    case 'sent':
      return new Date(m.sent_at ?? m.created_at).getTime() + SENT_PENDING_MS > now
    default:
      return false
  }
}

function hasPending(data: MessagesData | undefined): boolean {
  const now = Date.now()
  return !!data?.pages.some((p) => p.data.some((m) => isPending(m, now)))
}

/**
 * The messages log, newest first, paged with keyset cursors (`next_before`).
 * Polls every 5 s (all loaded pages) while any loaded message can still
 * change status (see isPending). Switching filters keeps the previous list on screen until the new
 * one arrives.
 */
export function useMessages(filters: MessageFilters = {}, { limit = MESSAGES_PAGE_SIZE } = {}) {
  const key: MessagesListKey = { status: filters.status, deviceId: filters.deviceId, limit }
  return useInfiniteQuery({
    queryKey: listKey(key),
    queryFn: ({ pageParam, signal }) => api<Page<Message>>(messagesPath(filters, limit, pageParam), { signal }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next_before ?? undefined,
    placeholderData: keepPreviousData,
    refetchInterval: (q) => (hasPending(q.state.data) ? MESSAGES_POLL_MS : false),
  })
}

/**
 * GET /app/messages/{id}: one message, for a deep link (`?message=<id>`)
 * whose message is not in the loaded list. Polls like the list while pending.
 */
export function useMessage(id: string | undefined, { enabled = true } = {}) {
  return useQuery({
    queryKey: [...messagesKey, 'one', id] as const,
    queryFn: ({ signal }) => api<Message>(`/app/messages/${encodeURIComponent(id!)}`, { signal }),
    enabled: enabled && id !== undefined,
    refetchInterval: (q) => (q.state.data && isPending(q.state.data) ? MESSAGES_POLL_MS : false),
  })
}

export interface SendMessageVars {
  input: SendMessageInput
  /** One per form opening, so a retry after a lost response is not sent twice. */
  idempotencyKey: string
}

/**
 * POST /app/messages. On success the new message is put at the top of every
 * cached list it belongs to (it shows at once), then the lists refetch.
 */
export function useSendMessage() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ input, idempotencyKey }: SendMessageVars) =>
      api<Message>('/app/messages', { method: 'POST', body: input, headers: { 'Idempotency-Key': idempotencyKey } }),
    onSuccess: (msg) => {
      for (const [queryKey, data] of qc.getQueriesData<MessagesData>({ queryKey: [...messagesKey, 'list'] })) {
        const k = queryKey[2] as MessagesListKey | undefined
        if (!data || !k) continue
        if ((k.status && k.status !== msg.status) || (k.deviceId && k.deviceId !== msg.device_id)) continue
        if (data.pages.some((p) => p.data.some((m) => m.id === msg.id))) continue // idempotent replay
        const [first, ...rest] = data.pages
        if (!first) continue
        qc.setQueryData<MessagesData>(queryKey, {
          ...data,
          pages: [{ ...first, data: [msg, ...first.data] }, ...rest],
        })
      }
      void qc.invalidateQueries({ queryKey: messagesKey })
      void qc.invalidateQueries({ queryKey: statsKey })
    },
    onError: (err) => {
      if (isApiError(err, 'email_not_verified')) void qc.invalidateQueries({ queryKey: meKey })
      if (isApiError(err, 'device_not_found')) void qc.invalidateQueries({ queryKey: devicesKey })
      // An earlier attempt with this key was queued (its response was lost)
      // and the details were edited since: show the message that went out.
      if (isApiError(err, 'idempotency_mismatch')) {
        void qc.invalidateQueries({ queryKey: messagesKey })
        void qc.invalidateQueries({ queryKey: statsKey })
      }
    },
  })
}

// ---- usage stats -----------------------------------------------------------

export const statsKey = ['stats'] as const
/** Poll interval of the Overview's usage stats (paused while the tab is hidden). */
export const STATS_POLL_MS = 15_000

/** GET /app/stats: plan, this month's usage and the last 24 h by status. */
export function useStats() {
  return useQuery({
    queryKey: statsKey,
    queryFn: ({ signal }) => api<Stats>('/app/stats', { signal }),
    refetchInterval: STATS_POLL_MS,
    refetchIntervalInBackground: false,
  })
}
