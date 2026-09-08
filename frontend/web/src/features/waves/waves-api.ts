import { api } from '@/lib/api-client';
import type { PaginatedResponse } from '@/types/api';
import type {
  ConsolidationGroupResponse,
  CreateWaveRequest,
  RulePreviewResponse,
  SelectionFieldResponse,
  SelectionRule,
  SelectionRuleResponse,
  WaveDetailResponse,
  WaveProgressResponse,
  WaveResponse,
  WaveStateName,
} from '@/types/waves';

/** GET /api/v1/waves -- paginated, optionally filtered by the WaveState NAME (not a code). */
export function listWaves(state?: WaveStateName, page = 0, size = 50) {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (state) params.set('state', state);
  return api.get<PaginatedResponse<WaveResponse>>(`/api/v1/waves?${params.toString()}`);
}

export const getWave = (id: number) => api.get<WaveDetailResponse>(`/api/v1/waves/${id}`);

export const getWaveProgress = (id: number) =>
  api.get<WaveProgressResponse>(`/api/v1/waves/${id}/progress`);

export const createWave = (body: CreateWaveRequest) =>
  api.post<WaveResponse>('/api/v1/waves', body);

export const releaseWave = (id: number) =>
  api.post<WaveDetailResponse>(`/api/v1/waves/${id}/release`, {});

export const cancelWave = (id: number) => api.post<WaveResponse>(`/api/v1/waves/${id}/cancel`, {});

export const markGroupReady = (waveId: number, groupId: number) =>
  api.post<ConsolidationGroupResponse>(
    `/api/v1/waves/${waveId}/consolidation-groups/${groupId}/ready`,
    {},
  );

// ── Selection rules (selection-rules sprint, Task 3 REST) ───────────────────

export interface SelectionRuleInput {
  name: string;
  description?: string;
  definition: SelectionRule;
}

/** GET /api/v1/wave-selection-rules/fields -- the field registry, in registry order. */
export const listSelectionRuleFields = () =>
  api.get<SelectionFieldResponse[]>('/api/v1/wave-selection-rules/fields');

export function listSelectionRules(page = 0, size = 50) {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  return api.get<PaginatedResponse<SelectionRuleResponse>>(
    `/api/v1/wave-selection-rules?${params.toString()}`,
  );
}

export const getSelectionRule = (id: number) =>
  api.get<SelectionRuleResponse>(`/api/v1/wave-selection-rules/${id}`);

export const createSelectionRule = (body: SelectionRuleInput) =>
  api.post<SelectionRuleResponse>('/api/v1/wave-selection-rules', body);

export const updateSelectionRule = (id: number, body: SelectionRuleInput) =>
  api.put<SelectionRuleResponse>(`/api/v1/wave-selection-rules/${id}`, body);

export const deleteSelectionRule = (id: number) =>
  api.delete<void>(`/api/v1/wave-selection-rules/${id}`);

/** Preview requires a SAVED rule -- evaluates the rule's own saved definition, no body. */
export const previewSelectionRule = (id: number) =>
  api.post<RulePreviewResponse>(`/api/v1/wave-selection-rules/${id}/preview`, {});
