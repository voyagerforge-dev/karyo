import { useDeferredValue, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router';
import { Inbox, Plus } from 'lucide-react';
import {
  MasterDetailLayout,
  MasterList,
  MasterListRow,
  FilterChips,
  DetailEmptyState,
} from '@/components/master-detail/master-detail';
import type { FilterChipOption } from '@/components/master-detail/master-detail';
import { SectionCard } from '@/components/control/section-card';
import { StatusPill } from '@/components/control/status-pill';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { usePermissions } from '@/hooks/use-permissions';
import { useAuth } from '@/components/auth/auth-provider';
import { useAvailableWork, useClaimWork, useMyWork, useReleaseWork } from '@/features/work/use-work';
import {
  WORK_TYPE_META,
  matchesWorkFilter,
  mergeWork,
  transportOrderToWorkItem,
  type WorkFilter,
} from './work-model';
import { useTransportOrders } from './use-tasks';
import { WORK_TYPES, refType, type WorkItemResponse, type WorkTypeName } from '@/types/work';
import { ManualMoveForm } from './manual-move-form';
import { TransportPane } from './transport-pane';
import { PickPane } from './pick-pane';
import { CountPane } from './count-pane';
import { ReceivePane } from './receive-pane';
import { ReplenishmentSection } from './replenishment-section';

const FILTERS: ReadonlyArray<FilterChipOption<WorkFilter>> = [
  { value: 'all', label: 'All' },
  ...WORK_TYPES.map((t) => ({ value: t as WorkFilter, label: WORK_TYPE_META[t].label })),
  // Row 22: paused transport orders are invisible to the merged work-inbox list above --
  // this chip switches the list to a directly-fetched paused-only query instead.
  { value: 'PAUSED', label: 'Paused' },
];

/** One row in the work-inbox master list. */
function WorkRow({
  item,
  active,
  onClick,
}: {
  item: WorkItemResponse;
  active: boolean;
  onClick: () => void;
}) {
  const meta = WORK_TYPE_META[item.workType as WorkTypeName];

  return (
    <MasterListRow tone={meta.tone} active={active} onClick={onClick} testId={`work-row-${item.ref}`}>
      <div className="flex items-center justify-between gap-2">
        <span className="min-w-0 flex-1 truncate text-[13px] font-semibold text-foreground">
          {item.summary}
        </span>
        <div className="flex shrink-0 items-center gap-2">
          <StatusPill label={meta.label} tone={meta.tone} />
          <span className="numeric text-[10.5px] font-semibold text-muted-foreground">{`P${item.priority}`}</span>
        </div>
      </div>
      {item.primaryLocation && (
        <div className="mt-1.5 truncate text-[12.5px] text-foreground/70">
          {item.primaryLocation}
          {item.destination ? ` → ${item.destination}` : ''}
        </div>
      )}
      {item.state === 'CLAIMED' && item.claimedBy && (
        <div className="mt-1 text-[11px] text-muted-foreground">Claimed · {item.claimedBy}</div>
      )}
    </MasterListRow>
  );
}

/**
 * Claim header + the per-work-type pane switch. Lives one level above the
 * individual panes (TransportPane/PickPane/CountPane/ReceivePane) so each
 * inherits claim for free instead of re-implementing it. Claim needs only
 * inventory-read (every real user holds it); the transport lifecycle actions
 * inside TransportPane are separately gated on task-write, and the
 * pick-confirm action inside PickPane is separately gated on
 * fulfillment-write (a fulfillment-owned mutation, not a tasks one). Release
 * is generic HERE for PICK/COUNT/RECEIVE (claimed-by-me is sufficient — no
 * intermediate state to 409 on; `GoodsReceiptService.release` has the same
 * shape as the pick/count precedent), but transport items suppress it here
 * and own it inside TransportPane instead, gated on state === RESERVED
 * (final review finding: the backend rejects releasing a STARTED transport
 * order).
 */
function WorkDetailPane({
  item,
  canWrite,
  currentOperatorId,
}: {
  item: WorkItemResponse;
  canWrite: boolean;
  currentOperatorId: string | null;
}) {
  const claimMutation = useClaimWork();
  const releaseMutation = useReleaseWork();

  const type = refType(item.ref);
  // Cross-docking sprint (fix-round): CROSS_DOCK now rides the regular work-inbox as well as
  // the Paused lane -- included here so it routes to TransportPane instead of falling through
  // to CountPane, which would be the wrong shape entirely.
  const isTransport =
    type === 'PUTAWAY' ||
    type === 'MOVE' ||
    type === 'REPLENISH' ||
    type === 'TRANSFER' ||
    type === 'CROSS_DOCK';
  const canClaim = item.state === 'OPEN';
  // Transport items own their Release affordance inside TransportPane, gated
  // on state === RESERVED (the backend only allows releasing a RESERVED
  // transport order — offering it once STARTED is a dead 409 affordance).
  // PICK/COUNT/RECEIVE have no such intermediate state, so they keep the
  // generic claimed-by-me gate here.
  const canRelease =
    !isTransport && item.claimedBy != null && item.claimedBy === currentOperatorId;

  return (
    <div className="space-y-3">
      {(canClaim || canRelease) && (
        <div className="flex items-center justify-end gap-2">
          {canClaim && (
            <Button
              onClick={() => claimMutation.mutate(item.ref)}
              disabled={claimMutation.isPending}
              data-testid="work-claim-button"
            >
              {claimMutation.isPending ? 'Claiming...' : 'Claim'}
            </Button>
          )}
          {canRelease && (
            <Button
              variant="outline"
              onClick={() => releaseMutation.mutate(item.ref)}
              disabled={releaseMutation.isPending}
              data-testid="work-release-button"
            >
              {releaseMutation.isPending ? 'Releasing...' : 'Release'}
            </Button>
          )}
        </div>
      )}
      {isTransport ? (
        <TransportPane workItem={item} canWrite={canWrite} currentOperatorId={currentOperatorId} />
      ) : type === 'PICK' ? (
        <PickPane workItem={item} />
      ) : type === 'RECEIVE' ? (
        <ReceivePane workItem={item} />
      ) : (
        <CountPane workItem={item} />
      )}
    </div>
  );
}

/**
 * Unified work-inbox master-detail (P3 Task 3+4, extended Task 6 with
 * RECEIVE): merges Task 1's available + claimed-by-me work into one list
 * across all 6 work types, replacing the old per-type DataTables. The detail
 * pane switches on the ref's type prefix: PUTAWAY/MOVE/REPLENISH/TRANSFER ->
 * TransportPane, PICK -> PickPane, RECEIVE -> ReceivePane, COUNT ->
 * CountPane. When the REPLENISH chip is active and nothing is selected, the
 * detail slot shows the replenishment needs section instead of the empty
 * state (a selected REPLENISH item still shows its TransportPane).
 */
export function TasksPage() {
  const { hasPermission } = usePermissions();
  const { userName } = useAuth();
  const canWrite = hasPermission('task-write');
  // The backend operatorId/claimedBy is a free string; the app's known
  // username is the operator identity for "assign to me" / claim / release.
  const currentOperatorId = userName ?? null;

  const [searchParams, setSearchParams] = useSearchParams();
  const [search, setSearch] = useState('');
  const deferredSearch = useDeferredValue(search);
  const [filter, setFilter] = useState<WorkFilter>(() => {
    const t = searchParams.get('type');
    return t != null && (WORK_TYPES as readonly string[]).includes(t) ? (t as WorkFilter) : 'all';
  });
  const [selectedRef, setSelectedRef] = useState<string | null>(null);
  // ⌘K palette deep link: ?create=1 opens the new-move form in the detail slot.
  const [formOpen, setFormOpen] = useState(() => searchParams.get('create') === '1');

  useEffect(() => {
    const t = searchParams.get('type');
    const create = searchParams.get('create');
    if (t === null && create === null) return;
    // Apply the deep-link params before stripping them — a command-palette
    // navigation to /tasks?type=... or /tasks?create=1 while this page is
    // already mounted only fires this effect (the useState initializers
    // above never re-run), so applying here is the only place it can land.
    if (t !== null && (WORK_TYPES as readonly string[]).includes(t)) setFilter(t as WorkFilter);
    if (create === '1') setFormOpen(true);
    setSearchParams({}, { replace: true });
  }, [searchParams, setSearchParams]);

  const availableQuery = useAvailableWork();
  const myQuery = useMyWork();
  const isLoading = availableQuery.isLoading || myQuery.isLoading;
  const items = useMemo(
    () => mergeWork(availableQuery.data ?? [], myQuery.data ?? []),
    [availableQuery.data, myQuery.data],
  );

  const isPausedFilter = filter === 'PAUSED';
  // Row 22: paused=true only when the chip is active -- the hook itself stays disabled
  // (no fetch) otherwise, per useTransportOrders' `enabled: paused != null`.
  const pausedQuery = useTransportOrders({ paused: isPausedFilter ? true : undefined });
  const pausedItems = useMemo(
    () => (pausedQuery.data?.content ?? []).map(transportOrderToWorkItem),
    [pausedQuery.data],
  );

  const filtered = useMemo(() => {
    const q = deferredSearch.trim().toLowerCase();
    const source = isPausedFilter ? pausedItems : items;
    return source.filter((item) => {
      if (!isPausedFilter && !matchesWorkFilter(item, filter)) return false;
      if (!q) return true;
      return (
        item.summary.toLowerCase().includes(q) ||
        (item.primaryLocation ?? '').toLowerCase().includes(q) ||
        (item.destination ?? '').toLowerCase().includes(q) ||
        item.ref.toLowerCase().includes(q)
      );
    });
  }, [items, pausedItems, isPausedFilter, deferredSearch, filter]);

  const listLoading = isPausedFilter ? pausedQuery.isLoading : isLoading;

  const selectedItem =
    items.find((item) => item.ref === selectedRef) ??
    pausedItems.find((item) => item.ref === selectedRef) ??
    null;

  function handleOpenCreate() {
    setFormOpen(true);
  }

  function handleCloseForm() {
    setFormOpen(false);
  }

  return (
    <div className="space-y-4" data-testid="tasks-page">
      <div className="flex items-center justify-between">
        <h1 className="font-display text-2xl font-bold tracking-[-0.02em]">Tasks</h1>
        {canWrite && (
          <Button onClick={handleOpenCreate} data-testid="new-move-button">
            <Plus className="mr-2 size-4" />
            New move
          </Button>
        )}
      </div>

      <MasterDetailLayout
        list={
          <MasterList
            searchValue={search}
            onSearchChange={setSearch}
            searchPlaceholder="Search work…"
            chips={<FilterChips options={FILTERS} value={filter} onChange={setFilter} />}
          >
            {listLoading ? (
              <>
                <Skeleton className="h-[76px] w-full rounded-xl" />
                <Skeleton className="h-[76px] w-full rounded-xl" />
                <Skeleton className="h-[76px] w-full rounded-xl" />
              </>
            ) : filtered.length === 0 ? (
              <p className="px-1 py-6 text-center text-sm text-muted-foreground">
                No work items match.
              </p>
            ) : (
              filtered.map((item) => (
                <WorkRow
                  key={item.ref}
                  item={item}
                  active={item.ref === selectedRef && !formOpen}
                  onClick={() => {
                    setSelectedRef(item.ref);
                    setFormOpen(false);
                  }}
                />
              ))
            )}
          </MasterList>
        }
        detail={
          formOpen ? (
            <SectionCard title="New move">
              <ManualMoveForm onClose={handleCloseForm} />
            </SectionCard>
          ) : selectedItem ? (
            <WorkDetailPane
              item={selectedItem}
              canWrite={canWrite}
              currentOperatorId={currentOperatorId}
            />
          ) : filter === 'REPLENISH' ? (
            <ReplenishmentSection />
          ) : (
            <DetailEmptyState
              icon={<Inbox className="size-8 opacity-40" />}
              message="Select a work item"
            />
          )
        }
      />
    </div>
  );
}
