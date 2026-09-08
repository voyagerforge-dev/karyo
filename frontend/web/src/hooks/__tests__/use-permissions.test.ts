import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook } from '@testing-library/react';
import { createElement } from 'react';

// Mock useAuth to return controlled permissions
const mockUseAuth = vi.fn();
vi.mock('@/components/auth/auth-provider', () => ({
  useAuth: () => mockUseAuth(),
}));

// Import after mocking
const { usePermissions } = await import('@/hooks/use-permissions');

function createWrapper() {
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return createElement('div', null, children);
  };
}

describe('usePermissions', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('hasPermission returns true for an included permission', () => {
    mockUseAuth.mockReturnValue({
      permissions: ['inventory-read', 'product-read', 'layout-read'],
    });
    const { result } = renderHook(() => usePermissions(), {
      wrapper: createWrapper(),
    });
    expect(result.current.hasPermission('inventory-read')).toBe(true);
  });

  it('hasPermission returns false for an excluded permission', () => {
    mockUseAuth.mockReturnValue({
      permissions: ['inventory-read', 'product-read'],
    });
    const { result } = renderHook(() => usePermissions(), {
      wrapper: createWrapper(),
    });
    expect(result.current.hasPermission('user-admin')).toBe(false);
  });

  it('hasAnyPermission returns true if at least one matches', () => {
    mockUseAuth.mockReturnValue({
      permissions: ['product-read'],
    });
    const { result } = renderHook(() => usePermissions(), {
      wrapper: createWrapper(),
    });
    expect(
      result.current.hasAnyPermission(['user-admin', 'product-read']),
    ).toBe(true);
  });

  it('hasAnyPermission returns false if none match', () => {
    mockUseAuth.mockReturnValue({
      permissions: ['inventory-read'],
    });
    const { result } = renderHook(() => usePermissions(), {
      wrapper: createWrapper(),
    });
    expect(
      result.current.hasAnyPermission(['user-admin', 'layout-write']),
    ).toBe(false);
  });

  it('returns the full permissions array', () => {
    const perms = ['inventory-read', 'product-read', 'layout-read'];
    mockUseAuth.mockReturnValue({ permissions: perms });
    const { result } = renderHook(() => usePermissions(), {
      wrapper: createWrapper(),
    });
    expect(result.current.permissions).toEqual(perms);
  });
});
