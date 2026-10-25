import { describe, expect, it } from 'vitest'
import { mockFetch } from '../test/mockFetch'
import { APK_URL, checkApk } from './apk'

describe('checkApk', () => {
  it('HEADs the same-origin URL without following redirects', async () => {
    const m = mockFetch([{ method: 'HEAD', path: APK_URL, opaqueRedirect: true }])
    await checkApk()
    const [call] = m.callsTo('HEAD', APK_URL)
    expect(call).toMatchObject({ redirect: 'manual', credentials: 'same-origin', cache: 'no-cache' })
  })

  it('treats the redirect to the release asset as available, size unknown', async () => {
    mockFetch([{ method: 'HEAD', path: APK_URL, opaqueRedirect: true }])
    await expect(checkApk()).resolves.toEqual({ available: true, size: null })
  })

  it('treats a visible 3xx (non-browser fetch) as available, size unknown', async () => {
    mockFetch([
      {
        method: 'HEAD',
        path: APK_URL,
        status: 302,
        headers: { location: 'https://github.com/o/r/releases/latest/download/smsgate.apk' },
      },
    ])
    await expect(checkApk()).resolves.toEqual({ available: true, size: null })
  })

  it('reads the size of a file served directly', async () => {
    mockFetch([
      {
        method: 'HEAD',
        path: APK_URL,
        headers: { 'content-type': 'application/vnd.android.package-archive', 'content-length': '2831155' },
      },
    ])
    await expect(checkApk()).resolves.toEqual({ available: true, size: 2831155 })
  })

  it('reports no size when the server sends no length', async () => {
    mockFetch([{ method: 'HEAD', path: APK_URL, headers: { 'content-type': 'application/octet-stream' } }])
    await expect(checkApk()).resolves.toEqual({ available: true, size: null })
  })

  it('is unavailable on 404, an HTML answer or a network error', async () => {
    mockFetch([{ method: 'HEAD', path: APK_URL, status: 404 }])
    await expect(checkApk()).resolves.toEqual({ available: false, size: null })

    mockFetch([{ method: 'HEAD', path: APK_URL, headers: { 'content-type': 'text/html; charset=utf-8' } }])
    await expect(checkApk()).resolves.toEqual({ available: false, size: null })

    mockFetch([{ method: 'HEAD', path: APK_URL, networkError: true }])
    await expect(checkApk()).resolves.toEqual({ available: false, size: null })
  })
})
