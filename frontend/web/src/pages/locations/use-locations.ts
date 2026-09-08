import {
  useQuery,
  useMutation,
  useQueryClient,
  type UseQueryResult,
} from '@tanstack/react-query';
import { toast } from 'sonner';
import { api } from '@/lib/api-client';
import type { PaginatedResponse } from '@/types/api';
import type {
  ZoneResponse,
  AreaResponse,
  LocationClusterResponse,
  LocationTypeResponse,
  LocationResponse,
  CreateZoneRequest,
  CreateAreaRequest,
  CreateLocationRequest,
  CreateLocationClusterRequest,
  LockLocationRequest,
} from '@/types/location';

export interface UseListOptions {
  page: number;
  size: number;
  sort?: string;
}

function buildUrl(base: string, options: UseListOptions, filters?: Record<string, unknown>): string {
  const params = new URLSearchParams();
  params.set('page', String(options.page));
  params.set('size', String(options.size));
  if (options.sort) params.set('sort', options.sort);
  if (filters) {
    for (const [key, value] of Object.entries(filters)) {
      if (value !== undefined && value !== null) {
        params.set(key, String(value));
      }
    }
  }
  return `${base}?${params.toString()}`;
}

// ---------- List hooks ----------

export function useZones(options: UseListOptions): UseQueryResult<PaginatedResponse<ZoneResponse>> {
  return useQuery({
    queryKey: ['zones', { page: options.page, size: options.size, sort: options.sort }],
    queryFn: () => api.get<PaginatedResponse<ZoneResponse>>(buildUrl('/api/v1/zones', options)),
    staleTime: 30_000,
  });
}

export function useAreas(options: UseListOptions & { zoneId?: number }): UseQueryResult<PaginatedResponse<AreaResponse>> {
  return useQuery({
    queryKey: ['areas', { zoneId: options.zoneId, page: options.page, size: options.size, sort: options.sort }],
    queryFn: () => api.get<PaginatedResponse<AreaResponse>>(
      buildUrl('/api/v1/areas', options, options.zoneId !== undefined ? { zoneId: options.zoneId } : {}),
    ),
    enabled: options.zoneId !== undefined,
    staleTime: 30_000,
  });
}

export function useClusters(options: UseListOptions & { areaId?: number }): UseQueryResult<PaginatedResponse<LocationClusterResponse>> {
  return useQuery({
    queryKey: ['clusters', { areaId: options.areaId, page: options.page, size: options.size, sort: options.sort }],
    queryFn: () => api.get<PaginatedResponse<LocationClusterResponse>>(
      buildUrl('/api/v1/location-clusters', options, options.areaId !== undefined ? { areaId: options.areaId } : {}),
    ),
    enabled: options.areaId !== undefined,
    staleTime: 30_000,
  });
}

export function useLocations(options: UseListOptions & { areaId?: number; zoneId?: number }): UseQueryResult<PaginatedResponse<LocationResponse>> {
  const filters: Record<string, unknown> = {};
  if (options.areaId !== undefined) filters.areaId = options.areaId;
  if (options.zoneId !== undefined) filters.zoneId = options.zoneId;

  return useQuery({
    queryKey: ['locations', { areaId: options.areaId, zoneId: options.zoneId, page: options.page, size: options.size, sort: options.sort }],
    queryFn: () => api.get<PaginatedResponse<LocationResponse>>(
      buildUrl('/api/v1/locations', options, filters),
    ),
    enabled: options.areaId !== undefined || options.zoneId !== undefined,
    staleTime: 30_000,
  });
}

/**
 * Fetch all locations for the tenant (flat, non-paginated convenience for the
 * master–detail list). The list endpoint's areaId/zoneId filters are optional,
 * so omitting them returns every location for the current tenant.
 */
export function useAllLocations(): UseQueryResult<PaginatedResponse<LocationResponse>> {
  return useQuery({
    queryKey: ['locations', 'all'],
    queryFn: () => api.get<PaginatedResponse<LocationResponse>>('/api/v1/locations?page=0&size=200&sort=orderIndex,asc'),
    staleTime: 30_000,
  });
}

// ---------- Dropdown lookup hooks ----------

/**
 * Fetch all zones for dropdown selectors (non-paginated convenience).
 */
export function useAllZones() {
  return useQuery({
    queryKey: ['zones', 'all'],
    queryFn: () => api.get<PaginatedResponse<ZoneResponse>>('/api/v1/zones?page=0&size=100'),
    staleTime: 60_000,
  });
}

/**
 * Fetch all clusters for dropdown selectors (non-paginated convenience).
 */
export function useAllClusters() {
  return useQuery({
    queryKey: ['clusters', 'all'],
    queryFn: () => api.get<PaginatedResponse<LocationClusterResponse>>('/api/v1/location-clusters?page=0&size=100'),
    staleTime: 60_000,
  });
}

/**
 * Fetch paginated location types.
 */
export function useLocationTypes(options: UseListOptions): UseQueryResult<PaginatedResponse<LocationTypeResponse>> {
  return useQuery({
    queryKey: ['location-types', { page: options.page, size: options.size, sort: options.sort }],
    queryFn: () => api.get<PaginatedResponse<LocationTypeResponse>>(buildUrl('/api/v1/location-types', options)),
    staleTime: 30_000,
  });
}

// ---------- Mutation hooks ----------

export function useCreateZone() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: CreateZoneRequest) =>
      api.post<ZoneResponse>('/api/v1/zones', data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['zones'] });
      toast.success('Zone created successfully');
    },
  });
}

export function useCreateArea() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: CreateAreaRequest) =>
      api.post<AreaResponse>('/api/v1/areas', data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['areas'] });
      toast.success('Area created successfully');
    },
  });
}

export function useCreateCluster() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: CreateLocationClusterRequest) =>
      api.post<LocationClusterResponse>('/api/v1/location-clusters', data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['clusters'] });
      toast.success('Cluster created successfully');
    },
  });
}

export function useCreateLocation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (data: CreateLocationRequest) =>
      api.post<LocationResponse>('/api/v1/locations', data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['locations'] });
      toast.success('Location created successfully');
    },
  });
}

export function useUpdateLocation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: Partial<CreateLocationRequest> }) =>
      api.put<LocationResponse>(`/api/v1/locations/${id}`, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['locations'] });
      toast.success('Location updated successfully');
    },
  });
}

export function useLockLocation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: LockLocationRequest }) =>
      api.post<LocationResponse>(`/api/v1/locations/${id}/lock`, data),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['locations'] });
      toast.success('Location lock updated');
    },
  });
}
