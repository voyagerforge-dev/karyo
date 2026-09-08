import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api-client';
import type { ReportDefinition, CreateReportDefinitionInput } from '@/types/reports';

const REPORTS_KEY = ['report-definitions'];

export function useReportDefinitions() {
  return useQuery({
    queryKey: REPORTS_KEY,
    queryFn: () => api.get<ReportDefinition[]>('/api/v1/report-definitions'),
  });
}

export function useCreateReportDefinition() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: CreateReportDefinitionInput) =>
      api.post<ReportDefinition>('/api/v1/report-definitions', input),
    onSuccess: () => qc.invalidateQueries({ queryKey: REPORTS_KEY }),
  });
}

export function useDeleteReportDefinition() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.delete(`/api/v1/report-definitions/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: REPORTS_KEY }),
  });
}
