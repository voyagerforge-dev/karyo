import { useDeferredValue, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router';
import { Package, Plus } from 'lucide-react';
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
import { Skeleton } from '@/components/ui/skeleton';
import { usePermissions } from '@/hooks/use-permissions';
import { useGoodsReceipts } from './use-receiving';
import { NewReceiptForm } from './new-receipt-form';
import { ReceiptDetail } from './receipt-detail';
import { getReceiptStatus, matchesReceiptFilter, type ReceiptFilter } from './receiving-status';
import { GOODS_RECEIPT_TYPE, type GoodsReceiptResponse } from '@/types/receiving';

const FILTERS: ReadonlyArray<FilterChipOption<ReceiptFilter>> = [
  { value: 'all', label: 'All' },
  { value: 'open', label: 'Open' },
  { value: 'paused', label: 'Paused' },
  { value: 'done', label: 'Done' },
];

/** One row in the master list. */
function ReceiptRow({
  receipt,
  active,
  onClick,
}: {
  receipt: GoodsReceiptResponse;
  active: boolean;
  onClick: () => void;
}) {
  const status = getReceiptStatus(receipt);

  return (
    <MasterListRow
      tone={status.tone}
      active={active}
      onClick={onClick}
      testId={`receipt-row-${receipt.id}`}
    >
      <div className="flex items-center justify-between gap-2">
        <div className="flex min-w-0 items-center gap-2">
          <span className="numeric text-[13px] font-semibold text-foreground">
            {receipt.receiptNumber}
          </span>
          {receipt.receiptType === GOODS_RECEIPT_TYPE.RETOUR && (
            <StatusPill label="RETOUR" tone="amber" />
          )}
        </div>
        <div className="flex shrink-0 items-center gap-2">
          {receipt.prio !== 50 && (
            <span className="numeric text-[10.5px] font-semibold text-muted-foreground">{`P${receipt.prio}`}</span>
          )}
          <StatusPill label={status.label} tone={status.tone} />
        </div>
      </div>
      <div className="mt-1.5 truncate text-[12.5px] text-foreground/70">
        {receipt.asns.length === 0
          ? 'Blind'
          : receipt.asns.length === 1
            ? receipt.asns[0].asnNumber
            : `${receipt.asns.length} ASNs`}
        {receipt.dockLocationName ? ` · ${receipt.dockLocationName}` : ''}
      </div>
    </MasterListRow>
  );
}

/**
 * Receiving master-detail list (B10-4 part 2). Rebuilt from the old
 * DataTable list onto the shared master-detail kit -- mirrors AsnsPage
 * (Task 2), the freshest precedent. Row click SELECTS (no navigation);
 * the detail pane's "Open workbench" button navigates to the operator
 * surface at /receiving/{id}.
 */
export function ReceivingPage() {
  const { hasPermission } = usePermissions();
  const canWrite = hasPermission('order-write');

  // ⌘K palette deep link: ?create=1 opens the create form in the detail slot.
  const [searchParams, setSearchParams] = useSearchParams();
  const [search, setSearch] = useState(() => searchParams.get('q') ?? '');
  const deferredSearch = useDeferredValue(search);
  const [filter, setFilter] = useState<ReceiptFilter>('all');
  const [selectedId, setSelectedId] = useState<number | undefined>();
  const [formOpen, setFormOpen] = useState(() => searchParams.get('create') === '1');

  useEffect(() => {
    if (searchParams.get('create') === null && searchParams.get('q') === null) return;
    // Only mutates the external URL system — no local setState in the effect.
    setSearchParams({}, { replace: true });
  }, [searchParams, setSearchParams]);

  // Page large so the client-side search/filter/select operates on a full set.
  const { data, isLoading } = useGoodsReceipts({ page: 0, size: 50 });
  const receipts = useMemo(() => data?.content ?? [], [data]);

  const filtered = useMemo(() => {
    const q = deferredSearch.trim().toLowerCase();
    return receipts.filter((r) => {
      if (!matchesReceiptFilter(r, filter)) return false;
      if (!q) return true;
      return (
        r.receiptNumber.toLowerCase().includes(q) ||
        r.asns.some((a) => a.asnNumber.toLowerCase().includes(q)) ||
        (r.carrierName ?? '').toLowerCase().includes(q)
      );
    });
  }, [receipts, deferredSearch, filter]);

  function handleOpenCreate() {
    setFormOpen(true);
  }

  function handleCloseForm() {
    setFormOpen(false);
  }

  return (
    <div className="space-y-4" data-testid="receiving-page">
      <div className="flex items-center justify-between">
        <h1 className="font-display text-2xl font-bold tracking-[-0.02em]">Receiving</h1>
        {canWrite && (
          <button
            type="button"
            onClick={handleOpenCreate}
            className="flex h-9 items-center gap-1.5 rounded-[9px] bg-primary px-3.5 text-[13px] font-bold text-primary-foreground"
          >
            <Plus className="size-4" />
            New receipt
          </button>
        )}
      </div>

      <MasterDetailLayout
        list={
          <MasterList
            searchValue={search}
            onSearchChange={setSearch}
            searchPlaceholder="Search receipt #, ASN # or carrier…"
            chips={<FilterChips options={FILTERS} value={filter} onChange={setFilter} />}
          >
            {isLoading ? (
              <>
                <Skeleton className="h-[76px] w-full rounded-xl" />
                <Skeleton className="h-[76px] w-full rounded-xl" />
                <Skeleton className="h-[76px] w-full rounded-xl" />
              </>
            ) : filtered.length === 0 ? (
              <p className="px-1 py-6 text-center text-sm text-muted-foreground">
                No receipts match.
              </p>
            ) : (
              filtered.map((receipt) => (
                <ReceiptRow
                  key={receipt.id}
                  receipt={receipt}
                  active={receipt.id === selectedId && !formOpen}
                  onClick={() => {
                    setSelectedId(receipt.id);
                    setFormOpen(false);
                  }}
                />
              ))
            )}
          </MasterList>
        }
        detail={
          formOpen ? (
            <SectionCard title="New receipt">
              <NewReceiptForm onClose={handleCloseForm} />
            </SectionCard>
          ) : selectedId != null ? (
            <ReceiptDetail receiptId={selectedId} canWrite={canWrite} />
          ) : (
            <DetailEmptyState
              icon={<Package className="size-8 opacity-40" />}
              message="Select a receipt"
            />
          )
        }
      />
    </div>
  );
}
