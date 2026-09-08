import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api-client';
import type { KpiDashboardResponse, ReportRange } from '@/types/insights';

export function useKpis(range: ReportRange) {
  return useQuery({
    queryKey: ['insights-kpis', range],
    queryFn: () => api.get<KpiDashboardResponse>(`/api/v1/insights/kpis?range=${range}`),
  });
}
