import { describe, expect, it } from 'vitest'
import { api, ApiError } from './client'
import { apiError, mockFetch } from '../test/mockFetch'

describe('api client', () => {
  it('returns parsed JSON and sends same-origin credentials without Content-Type on GET', async () => {
    const m = mockFetch([{ path: '/app/me', json: { id: 'u1', email: 'a@b.co' } }])
    const me = await api<{ id: string; email: string }>('/app/me')
    expect(me).toEqual({ id: 'u1', email: 'a@b.co' })
    const call = m.calls[0]!
    expect(call.method).toBe('GET')
    expect(call.credentials).toBe('same-origin')
    expect(call.headers['content-type']).toBeUndefined()
    expect(call.headers['accept']).toBe('application/json')
  })

  it('serialises the body and sets Content-Type on POST', async () => {
    const m = mockFetch([{ method: 'POST', path: '/app/login', status: 204 }])
    await api('/app/login', { method: 'POST', body: { email: 'a@b.co', password: 'pw' } })
    const call = m.calls[0]!
    expect(call.headers['content-type']).toBe('application/json')
    expect(call.body).toEqual({ email: 'a@b.co', password: 'pw' })
  })

  it.each(['POST', 'DELETE', 'PATCH', 'PUT'])('sets Content-Type on bodyless %s (backend 415 otherwise)', async (method) => {
    const m = mockFetch([{ method, path: '/app/x', status: 204 }])
    await api('/app/x', { method })
    expect(m.calls[0]!.headers['content-type']).toBe('application/json')
    expect(m.calls[0]!.body).toBeUndefined()
  })

  it('merges extra headers', async () => {
    const m = mockFetch([{ method: 'POST', path: '/app/messages', status: 202, json: { id: 'm1' } }])
    await api('/app/messages', { method: 'POST', body: {}, headers: { 'Idempotency-Key': 'k-1' } })
    expect(m.calls[0]!.headers['idempotency-key']).toBe('k-1')
    expect(m.calls[0]!.headers['content-type']).toBe('application/json')
  })

  it('returns undefined for 204', async () => {
    mockFetch([{ method: 'POST', path: '/app/logout', status: 204 }])
    await expect(api('/app/logout', { method: 'POST' })).resolves.toBeUndefined()
  })

  it('throws ApiError with the backend code for a JSON error body', async () => {
    mockFetch([{ method: 'POST', path: '/app/messages', ...apiError(422, 'invalid_to', 'to must be an E.164 number') }])
    const err = await api('/app/messages', { method: 'POST', body: {} }).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err).toMatchObject({ status: 422, code: 'invalid_to', message: 'to must be an E.164 number' })
  })

  it('maps a non-JSON error response to http_<status>', async () => {
    mockFetch([{ path: '/app/devices', status: 502, text: '<html>Bad Gateway</html>', headers: { 'content-type': 'text/html' } }])
    const err = await api('/app/devices').catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err).toMatchObject({ status: 502, code: 'http_502' })
  })

  it('maps a fetch rejection to network_error', async () => {
    mockFetch([{ path: '/app/me', networkError: true }])
    const err = await api('/app/me').catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err).toMatchObject({ status: 0, code: 'network_error' })
  })

  it('maps an unparseable 200 body to invalid_response', async () => {
    mockFetch([{ path: '/app/me', text: 'not json', headers: { 'content-type': 'application/json' } }])
    await expect(api('/app/me')).rejects.toMatchObject({ code: 'invalid_response', status: 200 })
  })
})
