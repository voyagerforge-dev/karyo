import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { api } from '@/lib/api-client';
import { useUser, useUsers } from '../use-users';
import type { PaginatedResponse } from '@/types/api';
import type { UserResponse } from '@/types/user';

vi.mock('@/lib/api-client', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn() },
  ApiError: class extends Error {},
}));

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
}));

const wrapper = ({ children }: { children: React.ReactNode }) => {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return React.createElement(QueryClientProvider, { client: qc }, children);
};

const mockUser: UserResponse = {
  id: 'abc-123',
  username: 'jdoe',
  email: 'jdoe@example.com',
  firstName: 'Jane',
  lastName: 'Doe',
  enabled: true,
  roles: ['OPERATOR'],
  tenantCode: 'ACME',
  warehouseId: null,
  createdTimestamp: 1709251200000,
};

describe('useUsers', () => {
  it('sends pagination, search, and status filters to the API', async () => {
    const response: PaginatedResponse<UserResponse> = {
      content: [mockUser],
      page: { number: 1, size: 100, totalElements: 101, totalPages: 2 },
    };
    vi.mocked(api.get).mockResolvedValue(response);

    const { result } = renderHook(
      () => useUsers({
        page: 1,
        size: 100,
        sort: 'username,asc',
        search: 'jane doe',
        enabled: false,
      }),
      { wrapper },
    );

    await waitFor(() => expect(result.current.data).toEqual(response));
    expect(api.get).toHaveBeenCalledWith(
      '/api/v1/users?page=1&size=100&sort=username%2Casc&search=jane+doe&enabled=false',
    );
  });
});

describe('useUser', () => {
  it('fetches a single user by id', async () => {
    vi.mocked(api.get).mockResolvedValue(mockUser);
    const { result } = renderHook(() => useUser('abc-123'), { wrapper });
    await waitFor(() => expect(result.current.data).toEqual(mockUser));
    expect(api.get).toHaveBeenCalledWith('/api/v1/users/abc-123');
  });
});
