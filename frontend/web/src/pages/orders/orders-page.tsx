import { useDeferredValue, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router';
import { Download, Plus, ScrollText } from 'lucide-react';
import { saveCsv } from '@/lib/document-actions';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Skeleton } from '@/components/ui/skeleton';
import {
  MasterDetailLayout,
  MasterList,
  MasterListRow,
  FilterChips,
  DetailEmptyState,
} from '@/components/master-detail/master-detail';
import type { FilterChipOption } from '@/components/master-detail/master-detail';
import { TONE_COLOR } from '@/components/master-detail/tones';
import { usePermissions } from '@/hooks/use-permissions';
import { useDeliveryOrders } from './use-orders';
import { OrderForm } from './order-form';
import { OrderDetail } from './order-detail';
import { getOrderStatus, matchesFilter, type OrderFilter } from './order-status';
import type { DeliveryOrderResponse } from '@/types/orders';

const FILTERS: ReadonlyArray<FilterChipOption<OrderFilter>> = [
  { value: 'all', label: 'All' },
  { value: 'Picking', label: 'Picking' },
  { value: 'Exception', label: 'Exception' },
  { value: 'Ready', label: 'Ready' },
];

function fmtShipBy(iso: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? '—'
    : d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

/** One row in the master list. */
function OrderRow({
  order,
  active,
  onClick,
}: {
  order: DeliveryOrderResponse;
  active: boolean;
  onClick: () => void;
}) {
  const status = getOrderStatus(order);
  const total = order.lines.length;
  const pickedLines = order.lines.filter(
    (l) => l.amount > 0 && l.pickedAmount + l.substitutedAmount >= l.amount,
  ).length;
  const pct = total > 0 ? Math.round((pickedLines / total) * 100) : 0;

  return (
    <MasterListRow tone={status.tone} active={active} onClick={onClick}>
      <div className="flex items-center justify-between gap-2">
        <span className="numeric text-[13px] font-semibold text-foreground">
          {order.orderNumber}
        </span>
        <div className="flex items-center gap-1.5">
          {order.prio < 50 && (
            <span className="numeric rounded bg-error px-1.5 py-0.5 text-[9px] font-bold text-destructive">
              PRIO
            </span>
          )}
          <span
            className="rounded-[20px] px-2 py-0.5 text-[10.5px] font-bold"
            style={{
              color: TONE_COLOR[status.tone],
              background: `${TONE_COLOR[status.tone]}22`,
            }}
          >
            {status.label}
          </span>
        </div>
      </div>
      <div className="mt-1.5 truncate text-[12.5px] text-foreground/70">
        {order.customerName ?? 'No customer'}
      </div>
      <div className="mt-2.5 flex items-center gap-2">
        <div className="h-1.5 flex-1 overflow-hidden rounded bg-background">
          <div
            className="h-full rounded"
            style={{ width: `${pct}%`, background: TONE_COLOR[status.tone] }}
          />
        </div>
        <span className="numeric text-[10.5px] text-muted-foreground">
          {pickedLines}/{total}
        </span>
      </div>
      <div className="mt-2 flex items-center justify-between text-[10.5px] text-muted-foreground/70">
        <span className="numeric">{total} lines</span>
        <span className="numeric">ship {fmtShipBy(order.deliveryDate)}</span>
      </div>
    </MasterListRow>
  );
}

export function OrdersPage() {
  const { hasPermission } = usePermissions();
  const canWrite = hasPermission('order-write');

  // ⌘K palette deep links: ?create=1 opens the create dialog, ?q= prefills
  // search. Read once for the initial state, then strip the params in an effect.
  const [searchParams, setSearchParams] = useSearchParams();
  const [search, setSearch] = useState(() => searchParams.get('q') ?? '');
  const deferredSearch = useDeferredValue(search);
  const [filter, setFilter] = useState<OrderFilter>('all');
  const [selectedId, setSelectedId] = useState<number | undefined>();
  const [formOpen, setFormOpen] = useState(() => searchParams.get('create') === '1');

  useEffect(() => {
    if (searchParams.get('create') === null && searchParams.get('q') === null) return;
    // Only mutates the external URL system — no local setState in the effect.
    setSearchParams({}, { replace: true });
  }, [searchParams, setSearchParams]);

  // Page large so the client-side search/filter/select operates on a full set.
  const { data, isLoading } = useDeliveryOrders({ page: 0, size: 50 });
  const orders = useMemo(() => data?.content ?? [], [data]);

  const filtered = useMemo(() => {
    const q = deferredSearch.trim().toLowerCase();
    return orders.filter((o) => {
      if (!matchesFilter(getOrderStatus(o), filter)) return false;
      if (!q) return true;
      return (
        o.orderNumber.toLowerCase().includes(q) ||
        (o.customerName ?? '').toLowerCase().includes(q)
      );
    });
  }, [orders, deferredSearch, filter]);

  const selected = useMemo(
    () => orders.find((o) => o.id === selectedId),
    [orders, selectedId],
  );

  return (
    <div className="space-y-4" data-testid="orders-page">
      <div className="flex items-center justify-between">
        <h1 className="font-display text-2xl font-bold tracking-[-0.02em]">Orders</h1>
        <div className="flex items-center gap-2.5">
          <button
            type="button"
            data-testid="export-csv-btn"
            onClick={() => saveCsv('/api/v1/delivery-orders/export.csv', 'delivery-orders.csv')}
            className="flex h-9 items-center gap-1.5 rounded-[9px] border border-border bg-card px-3.5 text-[13px] font-medium text-foreground/85 hover:bg-accent"
          >
            <Download className="size-[15px]" />
            Export
          </button>
          {canWrite && (
            <button
              type="button"
              onClick={() => setFormOpen(true)}
              className="flex h-9 items-center gap-1.5 rounded-[9px] bg-primary px-3.5 text-[13px] font-bold text-primary-foreground"
            >
              <Plus className="size-4" />
              New order
            </button>
          )}
        </div>
      </div>

      <MasterDetailLayout
        list={
          <MasterList
            searchValue={search}
            onSearchChange={setSearch}
            searchPlaceholder="Search order # or customer…"
            chips={
              <FilterChips options={FILTERS} value={filter} onChange={setFilter} />
            }
          >
            {isLoading ? (
              <>
                <Skeleton className="h-[88px] w-full rounded-xl" />
                <Skeleton className="h-[88px] w-full rounded-xl" />
                <Skeleton className="h-[88px] w-full rounded-xl" />
              </>
            ) : filtered.length === 0 ? (
              <p className="px-1 py-6 text-center text-sm text-muted-foreground">
                No orders match.
              </p>
            ) : (
              filtered.map((order) => (
                <OrderRow
                  key={order.id}
                  order={order}
                  active={order.id === selectedId}
                  onClick={() => setSelectedId(order.id)}
                />
              ))
            )}
          </MasterList>
        }
        detail={
          selected ? (
            <OrderDetail summary={selected} canWrite={canWrite} />
          ) : (
            <DetailEmptyState
              icon={<ScrollText className="size-8 opacity-40" />}
              message="Select an order to view its fulfillment workspace"
            />
          )
        }
      />

      {/* Create order dialog (reuses the existing OrderForm) */}
      <Dialog open={formOpen} onOpenChange={setFormOpen}>
        <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>Create Order</DialogTitle>
            <DialogDescription>
              Add header details and one or more order lines.
            </DialogDescription>
          </DialogHeader>
          <OrderForm onClose={() => setFormOpen(false)} />
        </DialogContent>
      </Dialog>
    </div>
  );
}
