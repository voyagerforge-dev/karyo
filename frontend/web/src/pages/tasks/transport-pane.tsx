import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { Ban, MapPin, Pause, Play, PlayCircle, UserCheck } from 'lucide-react';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Checkbox } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { SectionCard } from '@/components/control/section-card';
import { AttributeGrid } from '@/components/control/attribute-grid';
import { StatusPill } from '@/components/control/status-pill';
import type { EntityTone } from '@/components/master-detail/tones';
import { LocationPicker, type PickedLocation } from '@/pages/receiving/location-picker';
import { useReleaseWork } from '@/features/work/use-work';
import { api } from '@/lib/api-client';
import {
  useTransportOrder,
  useAssignTask,
  useStartTask,
  useCompleteTask,
  useCancelTask,
  usePauseTask,
  useResumeTask,
} from './use-tasks';
import { WORK_TYPE_META } from './work-model';
import { TRANSPORT_ORDER_STATE } from '@/types/tasks';
import { refSourceId, type WorkItemResponse, type WorkTypeName } from '@/types/work';
import type { UnitLoadResponse } from '@/types/inventory';

function fmtDateTime(iso: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString();
}

interface TransportPaneProps {
  workItem: WorkItemResponse;
  /** Whether the user holds task-write */
  canWrite: boolean;
  /** Current operator identity ("assign to me"); null falls back to a free entry. */
  currentOperatorId: string | null;
}

/** OrderState name -> rail/pill tone for the transport-order lifecycle. */
function stateTone(stateName: string): EntityTone {
  switch (stateName) {
    case 'CREATED':
      return 'grey';
    case 'RELEASED':
      return 'blue';
    case 'RESERVED':
      return 'amber';
    case 'STARTED':
      return 'lime';
    case 'FINISHED':
      return 'blue';
    case 'CANCELED':
      return 'red';
    default:
      return 'grey';
  }
}

/**
 * Transport-order (PUTAWAY/MOVE/REPLENISH) detail workspace -- ported from the
 * old task-detail-drawer.tsx Sheet into a master-detail pane (P3 Task 3).
 * Content, state gates and payload semantics are carried over verbatim; only
 * the chrome changed (SectionCard workspace instead of a Sheet). Claim lives
 * one level up at the pane-wrapper (tasks-page.tsx) so future PICK/COUNT
 * panes inherit it for free. Release is owned HERE instead (final review
 * finding): the backend only allows releasing a RESERVED transport order, so
 * the affordance needs `task.state` to gate correctly -- the wrapper only
 * knows claim ownership, which would offer a dead 409 once STARTED. The
 * wrapper's generic claimed-by-me Release still covers PICK/COUNT, which have
 * no such intermediate state.
 */
export function TransportPane({ workItem, canWrite, currentOperatorId }: TransportPaneProps) {
  const taskId = refSourceId(workItem.ref);
  const { data: task, isLoading } = useTransportOrder(taskId);
  const assignMutation = useAssignTask();
  const startMutation = useStartTask();
  const completeMutation = useCompleteTask();
  const cancelMutation = useCancelTask();
  const releaseMutation = useReleaseWork();
  const pauseMutation = usePauseTask();
  const resumeMutation = useResumeTask();

  const [confirmCancel, setConfirmCancel] = useState(false);
  const [completeOpen, setCompleteOpen] = useState(false);
  /** Destination picked in the complete dialog (prefilled to the suggestion). */
  const [completeLocation, setCompleteLocation] = useState<PickedLocation | null>(null);
  /** PT17: optional partial-confirm quantity — empty string means "full". */
  const [completeAmount, setCompleteAmount] = useState('');
  /** PT16: confirm-merge into an EXISTING unit load instead of a location. */
  const [mergeMode, setMergeMode] = useState(false);
  const [mergeUnitLoadId, setMergeUnitLoadId] = useState('');

  const parsedMergeUlId = Number(mergeUnitLoadId);
  const mergeUlLookup = useQuery({
    queryKey: ['unit-loads', 'detail', parsedMergeUlId],
    queryFn: () => api.get<UnitLoadResponse>(`/api/v1/unit-loads/${parsedMergeUlId}`),
    enabled: mergeMode && mergeUnitLoadId.trim() !== '' && parsedMergeUlId > 0,
    retry: false,
  });

  // Prefill the complete dialog's location with the finder's suggestion, and
  // clear the partial-qty/merge draft left over from a prior open (e.g. the
  // dialog was dismissed via Cancel rather than a successful submit).
  useEffect(() => {
    if (!completeOpen) return;
    if (task?.suggestedLocationId && task.suggestedLocationName) {
      setCompleteLocation({ id: task.suggestedLocationId, name: task.suggestedLocationName });
    }
    setCompleteAmount('');
    setMergeMode(false);
    setMergeUnitLoadId('');
  }, [completeOpen, task?.suggestedLocationId, task?.suggestedLocationName]);

  function resetCompleteDraft() {
    setCompleteOpen(false);
    setCompleteLocation(null);
    setCompleteAmount('');
    setMergeMode(false);
    setMergeUnitLoadId('');
  }

  if (isLoading || !task) {
    return (
      <div className="space-y-4 rounded-2xl border border-border bg-card p-6">
        <Skeleton className="h-6 w-1/2" />
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-40 w-full" />
      </div>
    );
  }

  const state = task.state;
  const isReleased = state === TRANSPORT_ORDER_STATE.RELEASED;
  const isReserved = state === TRANSPORT_ORDER_STATE.RESERVED;
  const isStarted = state === TRANSPORT_ORDER_STATE.STARTED;
  const isTerminal =
    state === TRANSPORT_ORDER_STATE.FINISHED || state === TRANSPORT_ORDER_STATE.CANCELED;
  const canCancel = !isTerminal;
  const isPaused = task.pausedAt != null;
  // Mirrors TaskService's PAUSABLE_STATES (CREATED/RELEASED/RESERVED/STARTED,
  // i.e. every pre-terminal state) — pause/resume are orthogonal to `state`.
  const canPause = !isTerminal && !isPaused;
  const canResume = !isTerminal && isPaused;
  const typeLabel = WORK_TYPE_META[task.transportType as WorkTypeName]?.label ?? task.transportType;
  // The backend only allows releasing a RESERVED transport order — once
  // STARTED, release always 409s (see WorkService/TransportOrderService).
  // Gate on state, not just claim ownership, so the button never offers a
  // dead action. Not folded into `canWrite`: claim/release intentionally use
  // the lower inventory-read bar the wrapper used to apply (see
  // WorkDetailPane's comment in tasks-page.tsx).
  const canRelease =
    isReserved && workItem.claimedBy != null && workItem.claimedBy === currentOperatorId;

  const isMutating =
    assignMutation.isPending ||
    startMutation.isPending ||
    completeMutation.isPending ||
    cancelMutation.isPending ||
    pauseMutation.isPending ||
    resumeMutation.isPending;

  function handleAssignToMe() {
    if (!currentOperatorId) return;
    assignMutation.mutate({ id: task!.id, operatorId: currentOperatorId });
  }

  function handleRelease() {
    releaseMutation.mutate(workItem.ref);
  }

  function handleStart() {
    startMutation.mutate(task!.id);
  }

  function handlePause() {
    pauseMutation.mutate(task!.id);
  }

  function handleResume() {
    resumeMutation.mutate(task!.id);
  }

  function handleConfirmComplete() {
    // PT17: an entered quantity requests a partial confirm; empty means "full".
    const amount = completeAmount.trim() !== '' ? Number(completeAmount) : undefined;

    // PT16: merge mode sends destinationUnitLoadId instead of a location — the
    // two destination shapes are mutually exclusive (400 if both are sent).
    if (mergeMode) {
      completeMutation.mutate(
        {
          id: task!.id,
          destinationUnitLoadId: Number(mergeUnitLoadId),
          ...(amount != null ? { amount } : {}),
        },
        { onSuccess: resetCompleteDraft },
      );
      return;
    }

    // Accept the suggestion (send {id} when the picked location IS the
    // suggestion), otherwise send the chosen override.
    const isSuggestion = completeLocation?.id === task!.suggestedLocationId;
    const body =
      completeLocation && !isSuggestion
        ? {
            id: task!.id,
            destinationLocationId: completeLocation.id,
            destinationLocationName: completeLocation.name,
            ...(amount != null ? { amount } : {}),
          }
        : { id: task!.id, ...(amount != null ? { amount } : {}) };
    completeMutation.mutate(body, { onSuccess: resetCompleteDraft });
  }

  function handleConfirmCancel() {
    cancelMutation.mutate(task!.id, { onSuccess: () => setConfirmCancel(false) });
  }

  return (
    <div className="space-y-4">
      <SectionCard>
        <div className="flex items-center gap-3">
          <h1 className="font-display numeric text-[20px] font-bold text-foreground">
            {task.orderNumber}
          </h1>
          <span data-testid="task-detail-state">
            <StatusPill label={task.stateName} tone={stateTone(task.stateName)} />
          </span>
          {/* PT15: non-null once this order chained onto a TRANSFER successor at
              completion -- kept minimal (no board-filter wiring), just a link over. */}
          {task.successorId != null && (
            <Badge variant="outline" asChild>
              <Link to={`/tasks?q=${task.successorId}`} data-testid="task-successor-chip">
                Continues in #{task.successorId}
              </Link>
            </Badge>
          )}
        </div>
        <p className="mt-1 text-[13px] text-foreground/70">Unit load {task.unitLoadLabel}</p>

        <div className="mt-5">
          <AttributeGrid
            items={[
              { label: 'Order #', value: task.orderNumber },
              { label: 'Type', value: task.transportType },
              { label: 'Unit Load', value: task.unitLoadLabel },
              { label: 'From', value: task.sourceLocationName },
              {
                label: 'To',
                value: task.destinationLocationName
                  ? task.destinationLocationName
                  : task.suggestedLocationName
                    ? `${task.suggestedLocationName} (suggested)`
                    : null,
              },
              { label: 'Prio', value: String(task.prio) },
              { label: 'Operator', value: task.operatorId },
              { label: 'External ref', value: task.externalNumber },
              { label: 'Started', value: task.started ? fmtDateTime(task.started) : null },
              { label: 'Finished', value: task.finished ? fmtDateTime(task.finished) : null },
              { label: 'Paused', value: task.pausedAt ? fmtDateTime(task.pausedAt) : null },
              { label: 'Note', value: task.note },
            ]}
          />
        </div>

        {/* PT18: pausedAt is orthogonal to `state`, so a closed order can never
            carry a live pause (assign/start/complete all guard against it while
            open) -- gate on !isTerminal so a stale stamp never shows here. */}
        {!isTerminal && isPaused && (
          <div
            className="mt-4 rounded-md border border-warning bg-warning/15 p-3 text-sm font-medium text-warning-foreground"
            data-testid="task-paused-banner"
          >
            Paused since {fmtDateTime(task.pausedAt)}
          </div>
        )}

        {/* Suggested destination — highlighted callout */}
        {(task.suggestedLocationName || task.destinationLocationName) && (
          <div
            className="mt-5 rounded-md border border-primary/40 bg-primary/10 p-3"
            data-testid="task-suggested-destination"
          >
            <div className="flex items-center gap-2 text-sm font-medium">
              <MapPin className="size-4" />
              {task.destinationLocationName ? 'Destination' : 'Suggested destination'}
            </div>
            <p className="mt-1 font-mono text-[13px]">
              {task.destinationLocationName ?? task.suggestedLocationName}
            </p>
            {!task.destinationLocationName && (
              <p className="mt-1 text-xs text-muted-foreground">
                The location finder picked this spot. Accept it on completion or override.
              </p>
            )}
          </div>
        )}

        {/* Actions by state — Release (claim-gated, not canWrite-gated) plus
            the canWrite-gated lifecycle actions share one row. */}
        {(canRelease || (canWrite && !isTerminal)) && (
          <div className="mt-5 flex flex-wrap items-center gap-2 border-t border-border pt-4">
            {canRelease && (
              <Button
                variant="outline"
                onClick={handleRelease}
                disabled={releaseMutation.isPending}
                data-testid="work-release-button"
              >
                {releaseMutation.isPending ? 'Releasing...' : 'Release'}
              </Button>
            )}
            {canWrite && !isTerminal && (
              <>
                {canCancel && (
                  <Button
                    variant="outline"
                    className="text-destructive"
                    onClick={() => setConfirmCancel(true)}
                    disabled={isMutating}
                    data-testid="task-cancel-button"
                  >
                    <Ban className="size-4" />
                    Cancel
                  </Button>
                )}
                {isReleased && (
                  <Button
                    onClick={handleAssignToMe}
                    disabled={isMutating || !currentOperatorId || isPaused}
                    data-testid="task-assign-button"
                  >
                    <UserCheck className="size-4" />
                    {assignMutation.isPending ? 'Assigning...' : 'Assign to me'}
                  </Button>
                )}
                {isReserved && (
                  <Button
                    onClick={handleStart}
                    disabled={isMutating || isPaused}
                    data-testid="task-start-button"
                  >
                    <PlayCircle className="size-4" />
                    {startMutation.isPending ? 'Starting...' : 'Start'}
                  </Button>
                )}
                {isStarted && (
                  <Button
                    onClick={() => setCompleteOpen(true)}
                    disabled={isMutating || isPaused}
                    data-testid="task-complete-button"
                  >
                    <MapPin className="size-4" />
                    Complete
                  </Button>
                )}
                {canPause && (
                  <Button
                    variant="outline"
                    onClick={handlePause}
                    disabled={isMutating}
                    data-testid="task-pause-button"
                  >
                    <Pause className="size-4" />
                    {pauseMutation.isPending ? 'Pausing...' : 'Pause'}
                  </Button>
                )}
                {canResume && (
                  <Button
                    onClick={handleResume}
                    disabled={isMutating}
                    data-testid="task-resume-button"
                  >
                    <Play className="size-4" />
                    {resumeMutation.isPending ? 'Resuming...' : 'Resume'}
                  </Button>
                )}
              </>
            )}
          </div>
        )}
      </SectionCard>

      {/* Complete confirm — location picker prefilled to the suggestion, or
          (PT16) a merge-into-unit-load target instead. */}
      <AlertDialog open={completeOpen} onOpenChange={setCompleteOpen}>
        <AlertDialogContent data-testid="task-complete-dialog">
          <AlertDialogHeader>
            <AlertDialogTitle>Complete {typeLabel}</AlertDialogTitle>
            <AlertDialogDescription>
              Confirm where the unit load was placed. Accept the suggested location or override
              it.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <div className="space-y-1.5">
            <div className={mergeMode ? 'pointer-events-none opacity-50' : undefined}>
              <LocationPicker
                inputId="task-complete-location"
                value={completeLocation}
                onChange={setCompleteLocation}
              />
            </div>
          </div>

          {/* PT16: confirm-merge into an existing unit load instead of a location. Only
              meaningful under the same precondition as the partial-qty field below
              (task.amount != null) -- a merge target still needs a source amount to move. */}
          {task.amount != null && (
            <div className="space-y-1.5">
              <div className="flex items-center gap-2">
                <Checkbox
                  id="task-complete-merge-toggle"
                  checked={mergeMode}
                  onCheckedChange={(v) => setMergeMode(v === true)}
                  data-testid="task-complete-merge-toggle"
                />
                <Label
                  htmlFor="task-complete-merge-toggle"
                  className="text-xs font-normal text-muted-foreground"
                >
                  Merge into unit load
                </Label>
              </div>
              {mergeMode && (
                <div className="space-y-1.5 pt-1">
                  <Label htmlFor="task-complete-merge-ul">Target unit load ID</Label>
                  <Input
                    id="task-complete-merge-ul"
                    data-testid="task-complete-merge-ul-input"
                    type="number"
                    min={1}
                    value={mergeUnitLoadId}
                    onChange={(e) => setMergeUnitLoadId(e.target.value)}
                  />
                  {mergeUlLookup.data && (
                    <p className="text-xs text-muted-foreground">
                      {mergeUlLookup.data.labelId} · {mergeUlLookup.data.storageLocationName}
                    </p>
                  )}
                  {mergeUlLookup.isError && (
                    <p className="text-xs text-destructive">Unit load not found</p>
                  )}
                </div>
              )}
            </div>
          )}

          {/* PT17: only meaningful when the order's own unit load carried exactly
              one live stock unit at creation — the backend 409s otherwise. */}
          {task.amount != null && (
            <div className="space-y-1.5">
              <Label htmlFor="task-complete-amount">Quantity (partial)</Label>
              <Input
                id="task-complete-amount"
                data-testid="task-complete-amount-input"
                type="number"
                min={0}
                placeholder={`Full: ${task.amount}`}
                value={completeAmount}
                onChange={(e) => setCompleteAmount(e.target.value)}
              />
            </div>
          )}

          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleConfirmComplete}
              disabled={
                (mergeMode
                  ? !mergeUnitLoadId.trim() ||
                    Number(mergeUnitLoadId) <= 0 ||
                    mergeUlLookup.isError ||
                    mergeUlLookup.isLoading
                  : !completeLocation) || completeMutation.isPending
              }
              data-testid="task-complete-confirm"
            >
              {completeMutation.isPending ? 'Completing...' : 'Confirm move'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Cancel confirmation */}
      <AlertDialog open={confirmCancel} onOpenChange={setConfirmCancel}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Cancel task?</AlertDialogTitle>
            <AlertDialogDescription>
              {`This will cancel "${task.orderNumber}" and release any location reservation. This cannot be undone.`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Keep task</AlertDialogCancel>
            <AlertDialogAction onClick={handleConfirmCancel}>Cancel task</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
