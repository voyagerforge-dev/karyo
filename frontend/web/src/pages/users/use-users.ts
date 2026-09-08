import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { api } from '@/lib/api-client';
import { sortingStateToString } from '@/lib/table-utils';
import type { PaginatedResponse } from '@/types/api';
import type {
  UserResponse,
  CreateUserRequest,
  UpdateUserRequest,
} from '@/types/user';

export { sortingStateToString };

interface UseListOptions {
  page: number;
  size: number;
  sort?: string;
  search?: string;
  enabled?: boolean;
}

/**
 * Fetch paginated users with server-side sort/pagination.
 */
export function useUsers(options: UseListOptions) {
  const { page, size, sort, search, enabled } = options;
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (sort) params.set('sort', sort);
  if (search) params.set('search', search);
  if (enabled != null) params.set('enabled', String(enabled));
  return useQuery({
    queryKey: ['users', { page, size, sort, search, enabled }],
    queryFn: () =>
      api.get<PaginatedResponse<UserResponse>>(`/api/v1/users?${params.toString()}`),
    staleTime: 30_000,
  });
}

/**
 * Single user by Keycloak id — detail-pane fetch.
 */
export function useUser(id: string | undefined) {
  return useQuery({
    queryKey: ['users', 'detail', id],
    queryFn: () => api.get<UserResponse>(`/api/v1/users/${id}`),
    enabled: id != null,
    staleTime: 30_000,
  });
}

/**
 * Create a new user.
 */
export function useCreateUser() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: CreateUserRequest) =>
      api.post<UserResponse>('/api/v1/users', data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users'] });
      toast.success('User created');
    },
  });
}

/**
 * Update an existing user (profile fields only).
 */
export function useUpdateUser(userId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: UpdateUserRequest) =>
      api.put<UserResponse>(`/api/v1/users/${userId}`, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users'] });
      toast.success('User updated');
    },
  });
}

/**
 * Deactivate a user.
 */
export function useDeactivateUser() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (userId: string) =>
      api.put<void>(`/api/v1/users/${userId}/deactivate`, {}),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users'] });
      toast.success('User deactivated');
    },
  });
}

/**
 * Reactivate a user.
 */
export function useReactivateUser() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (userId: string) =>
      api.put<void>(`/api/v1/users/${userId}/reactivate`, {}),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users'] });
      toast.success('User reactivated');
    },
  });
}

/**
 * Assign a role to a user.
 * CRITICAL: Role management uses query parameters, NOT request body.
 */
export function useAssignRole(userId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (role: string) =>
      api.put<void>(`/api/v1/users/${userId}/roles?action=assign&role=${role}`, {}),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users'] });
      toast.success('Role assigned');
    },
  });
}

/**
 * Revoke a role from a user.
 * CRITICAL: Role management uses query parameters, NOT request body.
 */
export function useRevokeRole(userId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (role: string) =>
      api.put<void>(`/api/v1/users/${userId}/roles?action=revoke&role=${role}`, {}),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['users'] });
      toast.success('Role revoked');
    },
  });
}
