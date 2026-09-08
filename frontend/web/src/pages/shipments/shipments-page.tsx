import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router';
import { Truck } from 'lucide-react';
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
import { getShipmentStatus } from '@/pages/packing/packing-status';
import { ShipDetail } from './ship-detail';
import { useShipments } from './use-shipping';

type ShipmentFilter = 'all' | 'packing' | 'packed' | 'shipping' | 'shipped';

const FILTERS: ReadonlyArray<FilterChipOption<ShipmentFilter>> = [
  { value: 'all', label: 'All' },
  { value: 'packing', label: 'Packing' },
  { value: 'packed', label: 'Packed' },
  { value: 'shipping', label: 'Shipping' },
  { value: 'shipped', label: 'Shipped' },
];

const FILTER_STATE: Record<Exclude<ShipmentFilter, 'all'>, number> = {
  packing: 640,
  packed: 650,
  shipping: 670,
  shipped: 680,
};

/** One row in the master list -- shipment #, order #, carrier·tracking when set, status pill. */
function ShipmentRow({
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
      {(shipment.carrierName || shipment.trackingNumber) && (
        <div className="mt-1 text-[12px] text-foreground/60">
          {shipment.carrierName ?? '—'}
          {shipment.trackingNumber ? ` · ${shipment.trackingNumber}` : ''}
        </div>
      )}
    </MasterListRow>
  );
}

/**
 * Shipments master-detail list (P4 Task 2) -- rebuilt from the old DataTable +
 * drawer onto the shared master-detail kit -- mirrors ReceivingPage (freshest
 * single-selection precedent). Row click SELECTS; the detail pane
 * (ship-detail.tsx) re-fetches the shipment by id and drives manifest/dispatch.
 *
 * Deep link: `?shipment={id}` preselects that row once the list has loaded -- how the wave
 * detail's consolidation-group table reaches a group's shipment, since there is no /shipments/{id}
 * route to link to. An id that is not on the page (another tenant's, one the list does not carry)
 * falls back to the plain list rather than an empty detail pane. A row click always wins over the
 * link from then on, so the param is left in the URL rather than rewritten away.
 */
export function ShipmentsPage() {
  const { hasPermission } = usePermissions();
  const canWrite = hasPermission('fulfillment-write');
  const { data, isLoading } = useShipments();
  const shipments = useMemo(() => data ?? [], [data]);

  const [searchParams] = useSearchParams();
  const [filter, setFilter] = useState<ShipmentFilter>('all');
  const [search, setSearch] = useState('');
  const [clickedId, setClickedId] = useState<number | undefined>();

  // Derived, not synchronised into state: the deep link only applies until the operator picks a
  // row of their own, and the list it has to be checked against arrives asynchronously. An effect
  // that copied it into state would have to wait for the fetch and then setState mid-effect.
  const linkedId = Number(searchParams.get('shipment'));
  const selectedId = clickedId ?? (shipments.some((s) => s.id === linkedId) ? linkedId : undefined);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return shipments.filter((s) => {
      if (filter !== 'all' && s.state !== FILTER_STATE[filter]) return false;
      if (!q) return true;
      return (
        s.shipmentNumber.toLowerCase().includes(q) ||
        (s.deliveryOrderNumber ?? '').toLowerCase().includes(q) ||
        (s.orders ?? []).some((o) => o.number.toLowerCase().includes(q)) ||
        (s.carrierName ?? '').toLowerCase().includes(q)
      );
    });
  }, [shipments, filter, search]);

  return (
    <div className="space-y-4" data-testid="shipments-page">
      <h1 className="font-display text-2xl font-bold tracking-[-0.02em]">Shipments</h1>

      <MasterDetailLayout
        list={
          <MasterList
            searchValue={search}
            onSearchChange={setSearch}
            searchPlaceholder="Search shipment, order # or carrier…"
            chips={<FilterChips options={FILTERS} value={filter} onChange={setFilter} />}
          >
            {isLoading ? (
              <>
                <Skeleton className="h-[68px] w-full rounded-xl" />
                <Skeleton className="h-[68px] w-full rounded-xl" />
              </>
            ) : filtered.length === 0 ? (
              <p className="px-1 py-6 text-center text-sm text-muted-foreground">
                No shipments match.
              </p>
            ) : (
              filtered.map((s) => (
                <ShipmentRow
                  key={s.id}
                  shipment={s}
                  active={s.id === selectedId}
                  onClick={() => setClickedId(s.id)}
                />
              ))
            )}
          </MasterList>
        }
        detail={
          selectedId != null ? (
            <ShipDetail shipmentId={selectedId} canWrite={canWrite} />
          ) : (
            <DetailEmptyState
              icon={<Truck className="size-8 opacity-40" />}
              message="Select a shipment"
            />
          )
        }
      />
    </div>
  );
}
