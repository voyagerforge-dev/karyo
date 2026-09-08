import { useMemo, useState } from 'react';
import { Box, Boxes, Download } from 'lucide-react';
import { cn } from '@/lib/utils';
import { saveCsv } from '@/lib/document-actions';
import { useAuth } from '@/components/auth/auth-provider';
import {
  MasterDetailLayout,
  MasterList,
  MasterListRow,
  FilterChips,
  DetailEmptyState,
} from '@/components/master-detail/master-detail';
import { useFixAssignments, useStockUnits, toReorderPointMap } from './use-inventory';
import {
  computeKpis,
  matchesQuery,
  sortGroups,
  toItemLocationGroups,
  withReorderPoints,
  type GroupAxis,
  type ItemLocationGroup,
} from './inventory-rows';
import {
  STATUS_FILTERS,
  passesFilter,
  toneBgClass,
  toneTextClass,
  type StatusFilter,
  type StatusTone,
} from './stock-status';
import { InventoryDetail } from './inventory-detail';

const PAGE_SIZE = 50;

const FILTER_OPTIONS = STATUS_FILTERS.map((f) => ({ value: f, label: f }));

function Kpi({
  label,
  value,
  tone,
  note,
}: {
  label: string;
  value: number;
  tone?: StatusTone;
  note?: string;
}) {
  return (
    <div className="rounded-2xl border border-border bg-card px-4 py-3">
      <div className="text-label">{label}</div>
      <div
        className={cn(
          'numeric mt-1.5 text-[22px] font-bold tracking-tight text-foreground',
          tone && toneTextClass(tone),
        )}
      >
        {value.toLocaleString()}
      </div>
      {note && <div className="mt-0.5 text-[10.5px] text-muted-foreground/70">{note}</div>}
    </div>
  );
}

export function InventoryPage() {
  const { tenantCode } = useAuth();

  // Server-side pagination; aggregation + filter/sort are client-side over the page.
  const [pageIndex] = useState(0);
  const { data, isLoading } = useStockUnits({ page: pageIndex, size: PAGE_SIZE });
  const { data: fixAssignments } = useFixAssignments();

  const units = useMemo(() => data?.content ?? [], [data]);

  // Aggregate LPN-level stock units into Item @ Location groups, then join
  // in the reorder point (fix-assignments load alongside stock-units).
  const reorderPoints = useMemo(() => toReorderPointMap(fixAssignments ?? []), [fixAssignments]);
  const groups = useMemo(
    () => withReorderPoints(toItemLocationGroups(units), reorderPoints),
    [units, reorderPoints],
  );

  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<StatusFilter>('All');
  const [axis, setAxis] = useState<GroupAxis>('item');
  const [selectedKey, setSelectedKey] = useState<string | null>(null);

  const visible = useMemo(() => {
    const filtered = groups.filter(
      (g) => passesFilter(g.status.status, filter) && matchesQuery(g, query),
    );
    return sortGroups(filtered, axis);
  }, [groups, filter, query, axis]);

  // Selected group: keep the explicit selection if still visible, else first row.
  const selected: ItemLocationGroup | undefined = useMemo(() => {
    if (selectedKey) {
      const found = visible.find((g) => g.key === selectedKey);
      if (found) return found;
    }
    return visible[0];
  }, [visible, selectedKey]);

  const kpis = useMemo(() => computeKpis(groups), [groups]);

  const segBtn = (active: boolean) =>
    cn(
      'h-7 rounded-[7px] px-3 text-[12px] font-semibold transition-colors',
      active ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:text-foreground',
    );

  return (
    <div data-testid="inventory-page">
      {/* Page heading */}
      <div className="mb-4 flex items-end justify-between gap-3">
        <div>
          <h1 className="font-display text-2xl text-foreground">Inventory</h1>
          <p className="mt-1 text-[13px] text-muted-foreground">
            Stock by item &amp; location across {tenantCode ?? 'this facility'} ·{' '}
            {isLoading ? 'loading…' : `${groups.length.toLocaleString()} item·location groups`}
          </p>
        </div>
        <div className="flex items-center gap-2.5">
          {/* By item / By location grouping toggle */}
          <div className="flex gap-1 rounded-[9px] border border-border bg-card p-[3px]">
            <button type="button" onClick={() => setAxis('item')} className={segBtn(axis === 'item')}>
              By item
            </button>
            <button
              type="button"
              onClick={() => setAxis('location')}
              className={segBtn(axis === 'location')}
            >
              By location
            </button>
          </div>
          <button
            type="button"
            data-testid="export-csv-btn"
            onClick={() => saveCsv('/api/v1/stock-units/export.csv', 'stock-units.csv')}
            className="flex h-9 items-center gap-1.5 rounded-[9px] border border-border bg-card px-3.5 text-[13px] font-medium text-foreground/85 hover:bg-accent"
          >
            <Download className="size-[15px]" />
            Export
          </button>
        </div>
      </div>

      {/* KPI strip — page-scoped totals over the aggregated groups (honest labels). */}
      <div className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
        <Kpi label="On-hand units" value={kpis.onHand} note="this page" />
        <Kpi label="Available" value={kpis.available} tone="lime" note="this page" />
        <Kpi label="Allocated" value={kpis.allocated} tone="amber" note="this page" />
        <Kpi label="Need reorder" value={kpis.needReorder} tone="amber" note="below reorder point" />
        <Kpi label="Stockouts" value={kpis.stockouts} tone="red" note="this page" />
      </div>

      <MasterDetailLayout
        list={
          <MasterList
            searchValue={query}
            onSearchChange={setQuery}
            searchPlaceholder="Search item, SKU, location, lot…"
            chips={
              <FilterChips
                options={FILTER_OPTIONS}
                value={filter}
                onChange={(v) => setFilter(v as StatusFilter)}
              />
            }
          >
            {isLoading ? (
              <p className="px-1 py-4 text-sm text-muted-foreground">Loading stock…</p>
            ) : visible.length === 0 ? (
              <p className="px-1 py-4 text-sm text-muted-foreground">No matching stock.</p>
            ) : (
              visible.map((g) => {
                const active = g.key === selected?.key;
                return (
                  <MasterListRow
                    key={g.key}
                    tone={g.status.tone}
                    active={active}
                    onClick={() => setSelectedKey(g.key)}
                  >
                    <div className="flex flex-col gap-1.5" data-testid={`inv-row-${g.sku}`}>
                      <div className="flex items-center gap-2">
                        <span className="truncate text-[13px] font-semibold text-foreground">
                          {g.name}
                        </span>
                        <span
                          className={cn(
                            'ml-auto flex-none rounded-full px-2 py-0.5 text-[11px] font-bold',
                            toneTextClass(g.status.tone),
                            toneBgClass(g.status.tone),
                          )}
                        >
                          {g.status.status}
                        </span>
                      </div>
                      <div className="flex items-center gap-1.5">
                        <span className="numeric text-[11.5px] text-foreground/70">{g.sku}</span>
                        <span className="size-[3px] flex-none rounded-full bg-muted-foreground/30" />
                        <span className="numeric text-[11.5px] text-muted-foreground">{g.location}</span>
                        {g.locType !== '—' && (
                          <span className="truncate text-[11px] text-muted-foreground/70">{g.locType}</span>
                        )}
                      </div>
                      <div className="flex items-center gap-2">
                        {g.lpnTracked ? (
                          <span className="numeric flex items-center gap-1 rounded-[5px] border border-border bg-background px-1.5 py-0.5 text-[10px] text-muted-foreground">
                            <Box className="size-3" strokeWidth={2} />
                            {g.lpns.length} {g.lpns.length === 1 ? 'LPN' : 'LPNs'}
                          </span>
                        ) : (
                          <span className="numeric flex items-center gap-1 text-[10px] tracking-wide text-muted-foreground/60">
                            <Boxes className="size-3" strokeWidth={2} />
                            LOOSE
                          </span>
                        )}
                        <span className="numeric ml-auto text-[13px] font-bold text-foreground">
                          {g.onHand.toLocaleString()}
                        </span>
                      </div>
                    </div>
                  </MasterListRow>
                );
              })
            )}
          </MasterList>
        }
        detail={
          selected ? (
            <div data-testid="inventory-detail">
              <InventoryDetail group={selected} />
            </div>
          ) : (
            <DetailEmptyState
              icon={<Box className="size-8 text-muted-foreground/50" strokeWidth={1.5} />}
              message={
                isLoading ? 'Loading stock…' : 'No stock to show. Adjust filters or search.'
              }
            />
          )
        }
      />
    </div>
  );
}
