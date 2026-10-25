import { useQuery } from '@tanstack/react-query'

/**
 * Same-origin download path. In production nginx answers it with a 302 to the
 * latest GitHub Release asset (APK_DOWNLOAD_URL, see web/nginx); a file
 * mounted under /downloads/ would be served directly instead.
 */
export const APK_URL = '/downloads/smsgate.apk'

export interface ApkInfo {
  available: boolean
  /** Bytes, when the APK is served directly with a Content-Length; null for a redirect. */
  size: number | null
}

const unavailable: ApkInfo = { available: false, size: null }

const REDIRECTS = new Set([301, 302, 303, 307, 308])

/**
 * HEAD the APK without following redirects: the target is another origin
 * (GitHub), which CSP connect-src 'self' would block, and its size is not
 * needed. A browser exposes the redirect only as an "opaqueredirect"
 * response; that means available, size unknown. A 2xx non-HTML answer is the
 * file itself. Never throws: a 404, a network error, or an HTML answer (the
 * dev server's SPA fallback) all mean "not available yet".
 */
export async function checkApk(signal?: AbortSignal): Promise<ApkInfo> {
  try {
    const res = await fetch(APK_URL, {
      method: 'HEAD',
      redirect: 'manual',
      credentials: 'same-origin',
      cache: 'no-cache',
      signal,
    })
    if (res.type === 'opaqueredirect' || REDIRECTS.has(res.status)) return { available: true, size: null }
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
