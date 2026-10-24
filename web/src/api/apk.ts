import { useQuery } from '@tanstack/react-query'

/** Served by nginx from a mounted volume (see web/nginx). */
export const APK_URL = '/downloads/smsgate.apk'

export interface ApkInfo {
  available: boolean
  /** Bytes, when the server sends Content-Length. */
  size: number | null
}

const unavailable: ApkInfo = { available: false, size: null }

/**
 * HEAD the APK. Never throws: a 404, a network error, or an HTML answer (the
 * dev server's SPA fallback) all mean "not available yet".
 */
export async function checkApk(signal?: AbortSignal): Promise<ApkInfo> {
  try {
    const res = await fetch(APK_URL, { method: 'HEAD', credentials: 'same-origin', cache: 'no-cache', signal })
    if (!res.ok || (res.headers.get('content-type') ?? '').includes('text/html')) return unavailable
    const len = Number(res.headers.get('content-length'))
    return { available: true, size: Number.isFinite(len) && len > 0 ? len : null }
  } catch {
    return unavailable
  }
}

export function useApk() {
  return useQuery({
    queryKey: ['apk'],
    queryFn: ({ signal }) => checkApk(signal),
    staleTime: 5 * 60_000,
    retry: false,
  })
}
