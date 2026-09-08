import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api-client';
import type { OccupancyResponse } from '@/types/insights';

export function useOccupancy() {
  return useQuery({
    queryKey: ['insights-occupancy'],
    queryFn: () => api.get<OccupancyResponse>('/api/v1/insights/occupancy'),
  });
}
