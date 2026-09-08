import { useDeferredValue, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router';
import { Box, Plus } from 'lucide-react';
import {
  DetailEmptyState,
  FilterChips,
  MasterDetailLayout,
  MasterList,
  type FilterChipOption,
} from '@/components/master-detail/master-detail';
import { useProducts } from '@/pages/products/use-products';
import type { ProductResponse } from '@/types/product';
import { buildItemView } from './item-model';
import { useItemStock } from './use-item-stock';
import { useItemAnalytics } from './use-item-analytics';
import { ItemListRow } from './item-list-row';
import { ItemDetail } from './item-detail';
import { ItemFormSheet } from './item-form-sheet';

type ItemFilter = 'All' | 'A' | 'B' | 'C';

const FILTERS: ReadonlyArray<FilterChipOption<ItemFilter>> = [
  { value: 'All', label: 'All' },
  { value: 'A', label: 'Class A' },
  { value: 'B', label: 'Class B' },
  { value: 'C', label: 'Class C' },
];

// Pull a generous page of products; the Items list searches/filters client-side
// over the current page (mirrors products/inventory pagination conventions).
const PAGE_SIZE = 100;

export function ItemsPage() {
  const { data, isLoading } = useProducts({ page: 0, size: PAGE_SIZE });
  const { stockByItem, isLoading: stockLoading } = useItemStock();
  const {
    forecastBySku,
    slottingBySku,
    forecastEntitled,
    slottingEntitled,
    forecastLoading,
    slottingLoading,
  } = useItemAnalytics();

  // REAL data → item view-models: identity from the product, stock from
  // aggregated stock units, forecast/slotting from the paid engines when
  // licensed. Absent data stays null (see item-model.ts) — never fabricated.
  const items = useMemo(
    () =>
      (data?.content ?? []).map((p) =>
        buildItemView(p, stockByItem.get(p.id), forecastBySku.get(p.number), slottingBySku.get(p.number)),
      ),
    [data, stockByItem, forecastBySku, slottingBySku],
  );

  // Products addressable by id — lets Edit hand ItemFormSheet the real
  // ProductResponse (ItemView is a derived read model, not the raw product).
  const productsById = useMemo(
    () => new Map((data?.content ?? []).map((p) => [p.id, p])),
    [data],
  );

  const [query, setQuery] = useState('');
  const deferredQuery = useDeferredValue(query);
  const [filter, setFilter] = useState<ItemFilter>('All');
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [searchParams, setSearchParams] = useSearchParams();
  // undefined = closed, null = create, ProductResponse = edit.
  // ⌘K palette deep link: ?create=1 opens the create sheet on a fresh mount
  // (the initializer below never re-runs after that).
  const [formProduct, setFormProduct] = useState<ProductResponse | null | undefined>(() =>
    searchParams.get('create') === '1' ? null : undefined,
  );

  useEffect(() => {
    const create = searchParams.get('create');
    if (create === null) return;
    // Apply the deep-link param before stripping it -- a command-palette
    // navigation to /items?create=1 while this page is already mounted only
    // fires this effect (the useState initializer above never re-runs), so
    // applying here is the only place it can land.
    if (create === '1') setFormProduct(null);
    setSearchParams({}, { replace: true });
  }, [searchParams, setSearchParams]);

  const filtered = useMemo(() => {
    const q = deferredQuery.trim().toLowerCase();
    return items.filter((it) => {
      if (filter !== 'All' && it.cls !== filter) return false;
      if (q && !(it.sku.toLowerCase().includes(q) || it.name.toLowerCase().includes(q) || it.category.toLowerCase().includes(q))) {
        return false;
      }
      return true;
    });
  }, [items, deferredQuery, filter]);

  // Selection: keep the chosen item if still visible, else fall back to the first.
  const selected = useMemo(
    () => filtered.find((it) => it.id === selectedId) ?? filtered[0] ?? null,
    [filtered, selectedId],
  );

  const totalElements = data?.page?.totalElements ?? items.length;

  return (
    <div data-testid="items-page">
      {/* Page heading */}
      <div className="mb-[18px] flex items-end justify-between">
        <div>
          <h1 className="font-display text-2xl text-foreground">Items</h1>
          <p className="mt-[5px] text-[13px] text-muted-foreground">
            Product catalog &middot; {totalElements.toLocaleString()} SKUs
          </p>
        </div>
        <button
          type="button"
          onClick={() => setFormProduct(null)}
          className="flex h-9 items-center gap-1.5 rounded-[10px] bg-primary px-[15px] text-[13px] font-bold text-primary-foreground transition-opacity hover:opacity-90"
        >
          <Plus className="size-4" strokeWidth={2.4} />
          New item
        </button>
      </div>

      <MasterDetailLayout
        list={
          <MasterList
            searchValue={query}
            onSearchChange={setQuery}
            searchPlaceholder="Search SKU, name, category…"
            chips={<FilterChips options={FILTERS} value={filter} onChange={setFilter} />}
          >
            {isLoading ? (
              <p className="px-1 py-4 text-sm text-muted-foreground">Loading items…</p>
            ) : filtered.length === 0 ? (
              <p className="px-1 py-4 text-sm text-muted-foreground">No items match.</p>
            ) : (
              filtered.map((it) => (
                <ItemListRow
                  key={it.id}
                  item={it}
                  active={selected?.id === it.id}
                  onSelect={() => setSelectedId(it.id)}
                />
              ))
            )}
          </MasterList>
        }
        detail={
          selected ? (
            <ItemDetail
              item={selected}
              forecastEntitled={forecastEntitled}
              slottingEntitled={slottingEntitled}
              stockLoading={stockLoading}
              forecastLoading={forecastLoading}
              slottingLoading={slottingLoading}
              onEdit={() => {
                const product = productsById.get(selected.id);
                if (product) setFormProduct(product);
              }}
            />
          ) : (
            <DetailEmptyState
              icon={<Box className="size-8" strokeWidth={1.4} />}
              message={isLoading ? 'Loading items…' : 'Select an item to view details'}
            />
          )
        }
      />

      <ItemFormSheet
        open={formProduct !== undefined}
        product={formProduct ?? undefined}
        onOpenChange={(o) => {
          if (!o) setFormProduct(undefined);
        }}
      />
    </div>
  );
}
