import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import {
  cancelWave,
  createSelectionRule,
  createWave,
  deleteSelectionRule,
  getWave,
  getWaveProgress,
  listSelectionRuleFields,
  listSelectionRules,
  listWaves,
  markGroupReady,
  previewSelectionRule,
  releaseWave,
  updateSelectionRule,
  type SelectionRuleInput,
} from './waves-api';
import type { CreateWaveRequest, WaveStateName } from '@/types/waves';

/** States where the wave is actively working -- picking or consolidating. */
const ACTIVE_STATES: readonly WaveStateName[] = ['RELEASED', 'PICKING', 'CONSOLIDATING'];

/** List waves, optionally filtered by state. `state` undefined = every state. */
export function useWaves(state?: WaveStateName) {
  return useQuery({
    queryKey: ['waves', 'list', { state }],
    queryFn: () => listWaves(state),
    staleTime: 10_000,
  });
}

/** A single wave's detail (header + orders + shortages + consolidation groups). */
export function useWave(id: number | undefined) {
  return useQuery({
    queryKey: ['waves', id],
    queryFn: () => getWave(id as number),
    enabled: id != null,
    staleTime: 5_000,
  });
}

/**
 * Live progress for a wave. Polls every 15s only while the wave is actively working
 * (RELEASED/PICKING/CONSOLIDATING) -- a PLANNED wave has nothing moving yet and a
 * COMPLETED/CANCELLED wave never changes again, so polling either is wasted traffic.
 */
export function useWaveProgress(id: number | undefined, waveState: WaveStateName | undefined) {
  const active = waveState != null && ACTIVE_STATES.includes(waveState);
  return useQuery({
    queryKey: ['waves', id, 'progress'],
    queryFn: () => getWaveProgress(id as number),
    enabled: id != null,
    refetchInterval: active ? 15_000 : false,
  });
}

function useInvalidateWaves() {
  const qc = useQueryClient();
  return (id?: number) => {
    void qc.invalidateQueries({ queryKey: ['waves', 'list'] });
    // A prefix match on ['waves', id] also covers the ['waves', id, 'progress'] query.
    if (id != null) void qc.invalidateQueries({ queryKey: ['waves', id] });
    void qc.invalidateQueries({ queryKey: ['orders'] });
    void qc.invalidateQueries({ queryKey: ['pick-orders'] });
  };
}

/** Create a wave. `mutate` takes the create body. */
export function useCreateWave() {
  const invalidate = useInvalidateWaves();
  return useMutation({
    mutationFn: (body: CreateWaveRequest) => createWave(body),
    onSuccess: (w) => {
      invalidate();
      toast.success(`Wave ${w.waveNumber} created`);
    },
  });
}

/** Release a PLANNED wave -- allocation, shortage handling, batch pick generation. */
export function useReleaseWave() {
  const invalidate = useInvalidateWaves();
  return useMutation({
    mutationFn: (id: number) => releaseWave(id),
    onSuccess: (d) => {
      invalidate(d.wave.id);
      toast.success(`Wave ${d.wave.waveNumber} released`);
    },
  });
}

/** Cancel a wave that hasn't completed yet. */
export function useCancelWave() {
  const invalidate = useInvalidateWaves();
  return useMutation({
    mutationFn: (id: number) => cancelWave(id),
    onSuccess: (w) => {
      invalidate(w.id);
      toast.success(`Wave ${w.waveNumber} canceled`);
    },
  });
}

/** Mark a consolidation group READY. `mutate` takes { waveId, groupId }. */
export function useMarkGroupReady() {
  const invalidate = useInvalidateWaves();
  return useMutation({
    mutationFn: ({ waveId, groupId }: { waveId: number; groupId: number }) =>
      markGroupReady(waveId, groupId),
    onSuccess: (_group, vars) => {
      invalidate(vars.waveId);
      toast.success('Consolidation group marked ready');
    },
  });
}

// ── Selection rules (selection-rules sprint, Task 5) ─────────────────────────

/** The field registry -- static per deployment, so it never needs a refetch within a session. */
export function useSelectionRuleFields() {
  return useQuery({
    queryKey: ['wave-rule-fields'],
    queryFn: listSelectionRuleFields,
    staleTime: Infinity,
  });
}

/** Named selection rules list, full definitions included (no separate detail fetch needed --
 *  `SelectionRuleResponse` already carries `definition` for the editor to consume). */
export function useSelectionRules() {
  return useQuery({
    queryKey: ['wave-rules', 'list'],
    queryFn: () => listSelectionRules(),
  });
}

function useInvalidateSelectionRules() {
  const qc = useQueryClient();
  return () => void qc.invalidateQueries({ queryKey: ['wave-rules'] });
}

/** Create a named selection rule. `mutate` takes the rule body. */
export function useCreateSelectionRule() {
  const invalidate = useInvalidateSelectionRules();
  return useMutation({
    mutationFn: (body: SelectionRuleInput) => createSelectionRule(body),
    onSuccess: (rule) => {
      invalidate();
      toast.success(`Rule "${rule.name}" saved`);
    },
  });
}

/** Update a named selection rule. `mutate` takes { id, body }. */
export function useUpdateSelectionRule() {
  const invalidate = useInvalidateSelectionRules();
  return useMutation({
    mutationFn: ({ id, body }: { id: number; body: SelectionRuleInput }) =>
      updateSelectionRule(id, body),
    onSuccess: (rule) => {
      invalidate();
      toast.success(`Rule "${rule.name}" saved`);
    },
  });
}

/** Delete a named selection rule (409 while any strategy still binds it). */
export function useDeleteSelectionRule() {
  const invalidate = useInvalidateSelectionRules();
  return useMutation({
    mutationFn: (id: number) => deleteSelectionRule(id),
    onSuccess: () => {
      invalidate();
      toast.success('Rule deleted');
    },
  });
}

/** Preview a SAVED rule's matches against the current eligible-order pool. */
export function usePreviewSelectionRule() {
  return useMutation({
    mutationFn: (id: number) => previewSelectionRule(id),
  });
}
