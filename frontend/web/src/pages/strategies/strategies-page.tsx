import { useMemo, useState } from 'react';
import { ClipboardList, Plus } from 'lucide-react';
import {
  MasterDetailLayout,
  MasterList,
  MasterListRow,
  FilterChips,
  DetailEmptyState,
} from '@/components/master-detail/master-detail';
import type { FilterChipOption } from '@/components/master-detail/master-detail';
import { SectionCard } from '@/components/control/section-card';
import { Skeleton } from '@/components/ui/skeleton';
import { usePermissions } from '@/hooks/use-permissions';
import type { OrderStrategyResponse, StorageStrategyResponse } from '@/types/strategies';
import { useOrderStrategies, useStorageStrategies } from './use-strategies';
import { OrderStrategyForm } from './order-strategy-form';
import { StorageStrategyForm } from './storage-strategy-form';
import { StrategyDetail } from './strategy-detail';

type Kind = 'order' | 'storage';
type Mode = 'view' | 'create' | 'edit';

const KIND_OPTIONS: Record<Kind, FilterChipOption<Kind>> = {
  order: { value: 'order', label: 'Order strategies' },
  storage: { value: 'storage', label: 'Storage strategies' },
};

/** Order strategy row -- name + the two flags that mattered most on the old
 * DataTable columns (locked stock / prefer complete), ported as muted text. */
function OrderRow({
  strategy,
  active,
  onClick,
}: {
  strategy: OrderStrategyResponse;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <MasterListRow tone="violet" active={active} onClick={onClick} testId={`strategy-row-order-${strategy.id}`}>
      <span className="font-mono text-[13px] font-semibold text-foreground">{strategy.name}</span>
      <div className="mt-1.5 text-[12.5px] text-foreground/70">
        Locked stock: {strategy.useLockedStock ? 'Yes' : 'No'} · Prefer complete:{' '}
        {strategy.preferComplete ? 'Yes' : 'No'}
      </div>
    </MasterListRow>
  );
}

/** Storage strategy row -- name + zone/mix flags, ported from the old columns. */
function StorageRow({
  strategy,
  active,
  onClick,
}: {
  strategy: StorageStrategyResponse;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <MasterListRow tone="blue" active={active} onClick={onClick} testId={`strategy-row-storage-${strategy.id}`}>
      <span className="font-mono text-[13px] font-semibold text-foreground">{strategy.name}</span>
      <div className="mt-1.5 text-[12.5px] text-foreground/70">
        Zone: {strategy.zoneId ?? '—'} · Mix item: {strategy.mixItem ? 'Yes' : 'No'} · Mix client:{' '}
        {strategy.mixClient ? 'Yes' : 'No'}
      </div>
    </MasterListRow>
  );
}

/**
 * Strategies master-detail (P4 Task 4 -- the last screen recompose). Chips
 * toggle order/storage kind (filtered to whichever kinds the caller can
 * read); rows select into a read workspace (strategy-detail.tsx); create/edit
 * render the existing 10-field/5-field forms directly in the detail slot,
 * mirroring receiving's NewReceiptForm pattern. The forms themselves
 * (order-strategy-form.tsx, storage-strategy-form.tsx, use-strategies.ts) are
 * unchanged -- only the shell around them moved off EntityDrawer/DataTable.
 */
export function StrategiesPage() {
  const { hasPermission } = usePermissions();
  const canReadOrder = hasPermission('order-read');
  const canWriteOrder = hasPermission('order-write');
  const canReadStorage = hasPermission('layout-read');
  const canWriteStorage = hasPermission('layout-write');

  const kindOptions = useMemo(() => {
    const opts: FilterChipOption<Kind>[] = [];
    if (canReadOrder) opts.push(KIND_OPTIONS.order);
    if (canReadStorage) opts.push(KIND_OPTIONS.storage);
    return opts;
  }, [canReadOrder, canReadStorage]);

  const [kind, setKind] = useState<Kind>(canReadOrder ? 'order' : 'storage');
  const [search, setSearch] = useState('');
  const [selectedId, setSelectedId] = useState<number | undefined>();
  const [mode, setMode] = useState<Mode>('view');

  const { data: orderStrategies, isLoading: orderLoading } = useOrderStrategies();
  const { data: storageStrategies, isLoading: storageLoading } = useStorageStrategies();

  const orderData = orderStrategies ?? [];
  const storageData = storageStrategies ?? [];

  const isLoading = kind === 'order' ? orderLoading : storageLoading;
  const canWrite = kind === 'order' ? canWriteOrder : canWriteStorage;

  const q = search.trim().toLowerCase();
  const filteredOrder = useMemo(
    () => (q ? orderData.filter((s) => s.name.toLowerCase().includes(q)) : orderData),
    [orderData, q],
  );
  const filteredStorage = useMemo(
    () => (q ? storageData.filter((s) => s.name.toLowerCase().includes(q)) : storageData),
    [storageData, q],
  );

  const selectedOrder = kind === 'order' ? orderData.find((s) => s.id === selectedId) : undefined;
  const selectedStorage = kind === 'storage' ? storageData.find((s) => s.id === selectedId) : undefined;

  function switchKind(next: Kind) {
    setKind(next);
    setSelectedId(undefined);
    setMode('view');
  }

  function openCreate() {
    setSelectedId(undefined);
    setMode('create');
  }

  function selectRow(id: number) {
    setSelectedId(id);
    setMode('view');
  }

  function closeForm() {
    setMode('view');
  }

  return (
    <div className="space-y-4" data-testid="strategies-page">
      <div className="flex items-center justify-between">
        <h1 className="font-display text-2xl font-bold tracking-[-0.02em]">Strategies</h1>
        {canWrite && (
          <button
            type="button"
            onClick={openCreate}
            data-testid="strategy-create"
            className="flex h-9 items-center gap-1.5 rounded-[9px] bg-primary px-3.5 text-[13px] font-bold text-primary-foreground"
          >
            <Plus className="size-4" />
            New strategy
          </button>
        )}
      </div>

      <MasterDetailLayout
        list={
          <MasterList
            searchValue={search}
            onSearchChange={setSearch}
            searchPlaceholder="Search strategy name…"
            chips={<FilterChips options={kindOptions} value={kind} onChange={switchKind} />}
          >
            {isLoading ? (
              <>
                <Skeleton className="h-[60px] w-full rounded-xl" />
                <Skeleton className="h-[60px] w-full rounded-xl" />
              </>
            ) : kind === 'order' ? (
              filteredOrder.length === 0 ? (
                <p className="px-1 py-6 text-center text-sm text-muted-foreground">
                  No order strategies match.
                </p>
              ) : (
                filteredOrder.map((s) => (
                  <OrderRow
                    key={s.id}
                    strategy={s}
                    active={mode === 'view' && s.id === selectedId}
                    onClick={() => selectRow(s.id)}
                  />
                ))
              )
            ) : filteredStorage.length === 0 ? (
              <p className="px-1 py-6 text-center text-sm text-muted-foreground">
                No storage strategies match.
              </p>
            ) : (
              filteredStorage.map((s) => (
                <StorageRow
                  key={s.id}
                  strategy={s}
                  active={mode === 'view' && s.id === selectedId}
                  onClick={() => selectRow(s.id)}
                />
              ))
            )}
          </MasterList>
        }
        detail={
          mode === 'create' ? (
            <SectionCard title={kind === 'order' ? 'New order strategy' : 'New storage strategy'}>
              {kind === 'order' ? (
                <OrderStrategyForm onClose={closeForm} />
              ) : (
                <StorageStrategyForm onClose={closeForm} />
              )}
            </SectionCard>
          ) : mode === 'edit' && kind === 'order' && selectedOrder ? (
            <SectionCard title={`Edit ${selectedOrder.name}`}>
              <OrderStrategyForm strategy={selectedOrder} onClose={closeForm} />
            </SectionCard>
          ) : mode === 'edit' && kind === 'storage' && selectedStorage ? (
            <SectionCard title={`Edit ${selectedStorage.name}`}>
              <StorageStrategyForm strategy={selectedStorage} onClose={closeForm} />
            </SectionCard>
          ) : kind === 'order' && selectedOrder ? (
            <StrategyDetail
              kind="order"
              strategy={selectedOrder}
              canWrite={canWriteOrder}
              onEdit={() => setMode('edit')}
            />
          ) : kind === 'storage' && selectedStorage ? (
            <StrategyDetail
              kind="storage"
              strategy={selectedStorage}
              canWrite={canWriteStorage}
              onEdit={() => setMode('edit')}
            />
          ) : (
            <DetailEmptyState
              icon={<ClipboardList className="size-8 opacity-40" />}
              message="Select a strategy"
            />
          )
        }
      />
    </div>
  );
}
