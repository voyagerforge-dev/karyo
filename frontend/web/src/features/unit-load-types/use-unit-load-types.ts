import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api-client';
import type {
  CreateUnitLoadTypeRequest,
  UnitLoadTypeResponse,
  UpdateUnitLoadTypeRequest,
} from '@/types/inventory';

const UNIT_LOAD_TYPES_KEY = ['unit-load-types'];

/** GET /api/v1/unit-load-types: inventory-read. Bare array (no pagination envelope). */
export function useUnitLoadTypes() {
  return useQuery({
    queryKey: UNIT_LOAD_TYPES_KEY,
    queryFn: () => api.get<UnitLoadTypeResponse[]>('/api/v1/unit-load-types'),
  });
}

export function useCreateUnitLoadType() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateUnitLoadTypeRequest) =>
      api.post<UnitLoadTypeResponse>('/api/v1/unit-load-types', body),
    onSuccess: () => qc.invalidateQueries({ queryKey: UNIT_LOAD_TYPES_KEY }),
  });
}

/**
 * PUT is a full-representation update (Row 16): `body` must always carry every field,
 * never just the ones the operator changed. See `UpdateUnitLoadTypeRequest`.
 */
export function useUpdateUnitLoadType(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: UpdateUnitLoadTypeRequest) =>
      api.put<UnitLoadTypeResponse>(`/api/v1/unit-load-types/${id}`, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: UNIT_LOAD_TYPES_KEY }),
  });
}
