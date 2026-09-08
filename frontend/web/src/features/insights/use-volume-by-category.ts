import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api-client';
import type { CategoryVolume, ReportRange } from '@/types/insights';

export function useVolumeByCategory(range: ReportRange) {
  return useQuery({
    queryKey: ['insights-volume-by-category', range],
    queryFn: () => api.get<CategoryVolume[]>(`/api/v1/insights/volume-by-category?range=${range}`),
  });
}
