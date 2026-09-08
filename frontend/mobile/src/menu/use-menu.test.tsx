import { describe, expect, it, vi, beforeEach } from 'vitest'
import { renderHook } from '@testing-library/react'

const mockKeycloak = vi.hoisted(() => ({ tokenParsed: { realm_access: { roles: [] as string[] } } }))
vi.mock('@/lib/keycloak', () => ({ keycloak: mockKeycloak }))
vi.mock('@/lib/offline/use-online', () => ({ useOnline: () => true }))

import { useMenu } from '@/menu/use-menu'

describe('useMenu', () => {
  beforeEach(() => { mockKeycloak.tokenParsed.realm_access.roles = [] })

  it('shows only items whose roles intersect the token', () => {
    mockKeycloak.tokenParsed.realm_access.roles = ['inventory-read']
    const { result } = renderHook(() => useMenu())
    expect(result.current.items.map((i) => i.id)).toEqual(['inquiry'])
  })

  it('operator role set sees all eight', () => {
    mockKeycloak.tokenParsed.realm_access.roles = [
      'inventory-read', 'inventory-write', 'task-write', 'order-write', 'fulfillment-write',
    ]
    const { result } = renderHook(() => useMenu())
    expect(result.current.items).toHaveLength(8)
  })

  it('no roles yields empty menu', () => {
    const { result } = renderHook(() => useMenu())
    expect(result.current.items).toHaveLength(0)
  })
})
