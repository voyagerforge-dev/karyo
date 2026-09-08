import { describe, expect, test, vi, beforeEach } from 'vitest'

vi.mock('@/lib/keycloak', () => ({ keycloak: { token: 'TKN' }, refreshSession: vi.fn().mockResolvedValue(true) }))
import { api, ApiError } from '@/lib/api-client'

describe('api client', () => {
  beforeEach(() => { vi.restoreAllMocks() })

  test('GET attaches bearer token and returns json', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ ref: 'PICK:1' }) })
    vi.stubGlobal('fetch', fetchMock)
    const r = await api.get<{ ref: string }>('/api/v1/work/mine')
    expect(r.ref).toBe('PICK:1')
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer TKN')
  })

  test('POST with empty body returns undefined on 204', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 204 }))
    await expect(api.post('/api/v1/work/PICK:1/release')).resolves.toBeUndefined()
  })

  test('non-ok throws ApiError carrying the ProblemDetail', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 409, json: async () => ({ type: 'about:blank', title: 'Conflict', status: 409, detail: 'taken' }) }))
    await expect(api.post('/api/v1/work/next')).rejects.toBeInstanceOf(ApiError)
  })
})
