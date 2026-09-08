import { useDeferredValue, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router';
import { Plus, Truck } from 'lucide-react';
import {
  MasterDetailLayout,
  MasterList,
  MasterListRow,
  FilterChips,
  DetailEmptyState,
} from '@/components/master-detail/master-detail';
import type { FilterChipOption } from '@/components/master-detail/master-detail';
import { TONE_COLOR } from '@/components/master-detail/tones';
import { Skeleton } from '@/components/ui/skeleton';
import { usePermissions } from '@/hooks/use-permissions';
import { useAsns } from './use-asns';
import { AsnForm } from './asn-form';
import { AsnDetail } from './asn-detail';
import { getAsnStatus, matchesAsnFilter, type AsnFilter } from './asn-status';
import type { AsnResponse } from '@/types/receiving';

const FILTERS: ReadonlyArray<FilterChipOption<AsnFilter>> = [
  { value: 'all', label: 'All' },
  { value: 'created', label: 'Created' },
  { value: 'receiving', label: 'Receiving' },
  { value: 'done', label: 'Done' },
];

function fmtExpected(iso: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? '—'
    : d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

/** One row in the master list. */
function AsnRow({
  asn,
  active,
  onClick,
}: {
  asn: AsnResponse;
  active: boolean;
  onClick: () => void;
}) {
  const status = getAsnStatus(asn);
  const pct = Math.min(asn.progressPercent, 100);

  return (
    <MasterListRow tone={status.tone} active={active} onClick={onClick}>
      <div className="flex items-center justify-between gap-2">
        <span className="numeric text-[13px] font-semibold text-foreground">{asn.asnNumber}</span>
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
      <div className="mt-1.5 truncate text-[12.5px] text-foreground/70">
        {asn.carrierName ?? 'No carrier'}
        {asn.supplierName ? ` · ${asn.supplierName}` : ''}
      </div>
      <div className="mt-2.5 flex items-center gap-2" data-testid="asn-progress">
        <div className="h-1.5 flex-1 overflow-hidden rounded bg-background">
          <div
            className="h-full rounded"
            style={{ width: `${pct}%`, background: TONE_COLOR[status.tone] }}
          />
        </div>
        <span className="numeric text-[10.5px] text-muted-foreground">{`${asn.progressPercent}%`}</span>
      </div>
      <div className="mt-2 flex items-center justify-between text-[10.5px] text-muted-foreground/70">
        <span className="numeric">{asn.lines.length} lines</span>
        <span className="numeric">expected {fmtExpected(asn.expectedDate)}</span>
      </div>
    </MasterListRow>
  );
}

export function AsnsPage() {
  const { hasPermission } = usePermissions();
  const canWrite = hasPermission('order-write');

  // ⌘K palette deep links: ?create=1 opens the create form, ?q= prefills
  // search. Read once for the initial state, then strip the params in an effect.
  const [searchParams, setSearchParams] = useSearchParams();
  const [search, setSearch] = useState(() => searchParams.get('q') ?? '');
  const deferredSearch = useDeferredValue(search);
  const [filter, setFilter] = useState<AsnFilter>('all');
  const [selectedId, setSelectedId] = useState<number | undefined>();
  const [formOpen, setFormOpen] = useState(() => searchParams.get('create') === '1');
  const [editingAsn, setEditingAsn] = useState<AsnResponse | undefined>();

  useEffect(() => {
    if (searchParams.get('create') === null && searchParams.get('q') === null) return;
    // Only mutates the external URL system — no local setState in the effect.
    setSearchParams({}, { replace: true });
  }, [searchParams, setSearchParams]);

  // Page large so the client-side search/filter/select operates on a full set.
  const { data, isLoading } = useAsns({ page: 0, size: 50 });
  const asns = useMemo(() => data?.content ?? [], [data]);

  const filtered = useMemo(() => {
    const q = deferredSearch.trim().toLowerCase();
    return asns.filter((a) => {
      if (!matchesAsnFilter(a, filter)) return false;
      if (!q) return true;
      return (
        a.asnNumber.toLowerCase().includes(q) ||
        (a.externalNumber ?? '').toLowerCase().includes(q) ||
        (a.carrierName ?? '').toLowerCase().includes(q) ||
        (a.supplierName ?? '').toLowerCase().includes(q)
      );
    });
  }, [asns, deferredSearch, filter]);

  function handleOpenCreate() {
    setEditingAsn(undefined);
    setFormOpen(true);
  }

  function handleEdit(asn: AsnResponse) {
    setEditingAsn(asn);
    setFormOpen(true);
  }

  function handleCloseForm() {
    setFormOpen(false);
    setEditingAsn(undefined);
  }

  return (
    <div className="space-y-4" data-testid="asns-page">
      <div className="flex items-center justify-between">
        <h1 className="font-display text-2xl font-bold tracking-[-0.02em]">ASNs</h1>
        {canWrite && (
          <button
            type="button"
            onClick={handleOpenCreate}
            className="flex h-9 items-center gap-1.5 rounded-[9px] bg-primary px-3.5 text-[13px] font-bold text-primary-foreground"
          >
            <Plus className="size-4" />
            New ASN
          </button>
        )}
      </div>

      <MasterDetailLayout
        list={
          <MasterList
            searchValue={search}
            onSearchChange={setSearch}
            searchPlaceholder="Search ASN #, external #, carrier or supplier…"
            chips={<FilterChips options={FILTERS} value={filter} onChange={setFilter} />}
          >
            {isLoading ? (
              <>
                <Skeleton className="h-[96px] w-full rounded-xl" />
                <Skeleton className="h-[96px] w-full rounded-xl" />
                <Skeleton className="h-[96px] w-full rounded-xl" />
              </>
            ) : filtered.length === 0 ? (
              <p className="px-1 py-6 text-center text-sm text-muted-foreground">No ASNs match.</p>
            ) : (
              filtered.map((asn) => (
                <AsnRow
                  key={asn.id}
                  asn={asn}
                  active={asn.id === selectedId && !formOpen}
                  onClick={() => {
                    setSelectedId(asn.id);
                    setFormOpen(false);
                  }}
                />
              ))
            )}
          </MasterList>
        }
        detail={
          formOpen ? (
            <AsnForm asn={editingAsn} onClose={handleCloseForm} />
          ) : selectedId != null ? (
            <AsnDetail asnId={selectedId} canWrite={canWrite} onEdit={handleEdit} />
          ) : (
            <DetailEmptyState
              icon={<Truck className="size-8 opacity-40" />}
              message="Select an ASN"
            />
          )
        }
      />
    </div>
  );
}
