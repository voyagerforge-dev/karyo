import { useMemo, useState } from 'react';
import { PackageCheck } from 'lucide-react';
import {
  MasterDetailLayout,
  MasterList,
  MasterListRow,
  FilterChips,
  DetailEmptyState,
} from '@/components/master-detail/master-detail';
import type { FilterChipOption } from '@/components/master-detail/master-detail';
import { StatusPill } from '@/components/control/status-pill';
import { Skeleton } from '@/components/ui/skeleton';
import { usePermissions } from '@/hooks/use-permissions';
import type { Shipment } from '@/types/shipments';
import { PackDetail, type PackSelection } from './pack-detail';
import { getShipmentStatus } from './packing-status';
import { useReadyToPack, useShipments, type OrderBoundPickOrder } from './use-packing';

type PackingFilter = 'ready' | 'inProgress';

const FILTERS: ReadonlyArray<FilterChipOption<PackingFilter>> = [
  { value: 'ready', label: 'Ready to pack' },
  { value: 'inProgress', label: 'In progress' },
];

/** Ready-to-pack row: selecting it opens (or resumes) packing -- a write action,
 * so both the click handler and its testid are gated on canWrite. */
function ReadyRow({
  po,
  active,
  canWrite,
  onClick,
}: {
  po: OrderBoundPickOrder;
  active: boolean;
  canWrite: boolean;
  onClick: () => void;
}) {
  return (
    <MasterListRow
      tone="lime"
      active={active}
      onClick={canWrite ? onClick : () => {}}
      testId={canWrite ? `pack-btn-${po.id}` : undefined}
    >
      <div className="flex items-center justify-between gap-2">
        <span className="numeric text-[13px] font-semibold text-foreground">
          {po.deliveryOrderNumber}
        </span>
        <StatusPill label="Picked" tone="lime" />
      </div>
      <div className="mt-1.5 text-[12.5px] text-foreground/70">
        {po.picks.length} {po.picks.length === 1 ? 'line' : 'lines'}
      </div>
    </MasterListRow>
  );
}

/** In-progress (PACKING) shipment row -- always selectable; the pane itself
 * gates the confirm form on canWrite. */
function InProgressRow({
  shipment,
  active,
  onClick,
}: {
  shipment: Shipment;
  active: boolean;
  onClick: () => void;
}) {
  const status = getShipmentStatus(shipment);
  return (
    <MasterListRow
      tone={status.tone}
      active={active}
      onClick={onClick}
      testId={`shipment-row-${shipment.id}`}
    >
      <div className="flex items-center justify-between gap-2">
        <span className="numeric text-[13px] font-semibold text-foreground">
          {shipment.shipmentNumber}
        </span>
        <StatusPill label={status.label} tone={status.tone} />
      </div>
      <div className="mt-1.5 text-[12.5px] text-foreground/70">
        {shipment.deliveryOrderNumber ?? (
          <span data-testid={`shipment-orders-chip-${shipment.id}`}>
            {shipment.orders?.length ?? 0} orders
          </span>
        )}
      </div>
    </MasterListRow>
  );
}

/**
 * Packing master-detail list (P4 Task 1). Two heterogeneous row types --
 * PICKED pick orders ready to pack, and PACKING(640) shipments already in
 * progress -- toggled by the Ready/In-progress chips (no "all", the row
 * shapes don't merge into one list). Selection identity is carried by
 * PackSelection so the detail pane (pack-detail.tsx) can re-derive whichever
 * entity it needs without threading full objects through page state.
 */
export function PackingPage() {
  const { hasPermission } = usePermissions();
  const canWrite = hasPermission('fulfillment-write');

  const ready = useReadyToPack();
  const shipments = useShipments();
  const inProgress = useMemo(
    () => (shipments.data ?? []).filter((s) => s.state === 640),
    [shipments.data],
  );

  const [filter, setFilter] = useState<PackingFilter>('ready');
  const [selection, setSelection] = useState<PackSelection | null>(null);
  const [search, setSearch] = useState('');

  const isLoading = filter === 'ready' ? ready.isLoading : shipments.isLoading;

  const q = search.trim().toLowerCase();
  const filteredReady = useMemo(
    () => (q ? ready.data.filter((po) => po.deliveryOrderNumber.toLowerCase().includes(q)) : ready.data),
    [ready.data, q],
  );
  const filteredInProgress = useMemo(
    () =>
      q
        ? inProgress.filter(
            (s) =>
              s.shipmentNumber.toLowerCase().includes(q) ||
              (s.deliveryOrderNumber ?? '').toLowerCase().includes(q) ||
              (s.orders ?? []).some((o) => o.number.toLowerCase().includes(q)),
          )
        : inProgress,
    [inProgress, q],
  );

  function selectReady(po: OrderBoundPickOrder) {
    setSelection({ mode: 'ready', pickOrderId: po.id, deliveryOrderId: po.deliveryOrderId });
  }

  function selectInProgress(s: Shipment) {
    setSelection({ mode: 'inProgress', shipmentId: s.id, deliveryOrderId: s.deliveryOrderId });
  }

  return (
    <div className="space-y-4" data-testid="packing-page">
      <h1 className="font-display text-2xl font-bold tracking-[-0.02em]">Packing</h1>

      <MasterDetailLayout
        list={
          <MasterList
            searchValue={search}
            onSearchChange={setSearch}
            searchPlaceholder="Search order or shipment #…"
            chips={<FilterChips options={FILTERS} value={filter} onChange={setFilter} />}
          >
            {isLoading ? (
              <>
                <Skeleton className="h-[68px] w-full rounded-xl" />
                <Skeleton className="h-[68px] w-full rounded-xl" />
              </>
            ) : filter === 'ready' ? (
              filteredReady.length === 0 ? (
                <p className="px-1 py-6 text-center text-sm text-muted-foreground">
                  Nothing ready to pack.
                </p>
              ) : (
                filteredReady.map((po) => (
                  <ReadyRow
                    key={po.id}
                    po={po}
                    canWrite={canWrite}
                    active={selection?.mode === 'ready' && selection.pickOrderId === po.id}
                    onClick={() => selectReady(po)}
                  />
                ))
              )
            ) : filteredInProgress.length === 0 ? (
              <p className="px-1 py-6 text-center text-sm text-muted-foreground">
                Nothing in progress.
              </p>
            ) : (
              filteredInProgress.map((s) => (
                <InProgressRow
                  key={s.id}
                  shipment={s}
                  active={selection?.mode === 'inProgress' && selection.shipmentId === s.id}
                  onClick={() => selectInProgress(s)}
                />
              ))
            )}
          </MasterList>
        }
        detail={
          selection ? (
            <PackDetail selection={selection} canWrite={canWrite} />
          ) : (
            <DetailEmptyState
              icon={<PackageCheck className="size-8 opacity-40" />}
              message="Select an order to pack"
            />
          )
        }
      />
    </div>
  );
}
