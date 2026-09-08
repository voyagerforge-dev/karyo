/**
 * Real item view-model — identity from the product catalog, operational
 * numbers from real aggregate endpoints where they exist, honest absence
 * where they don't.
 *
 *   IDENTITY (always, from ProductResponse): sku/number, name, category
 *     (tradeGroup), UoM (itemUnit.name), weight, dims, barcode (numbers),
 *     pack size (packagingUnits), lot-tracked (lotMandatory +
 *     bestBeforeMandatory).
 *   STOCK (from `useItemStock` — Task 4): available/reserved/held/onHand +
 *     where-stored, aggregated from real LPN-level stock units. `null` when
 *     the item has no stock units at all.
 *   FORECAST (from `useForecasts`, paid `forecasting` license): demand +
 *     reorder-point suggestion. `null` when unlicensed, or when the SKU has
 *     no recent pick history to forecast from.
 *   SLOTTING (from `useSlotting`, paid `slotting` license): ABC class +
 *     re-slot recommendation. `null` when unlicensed, or when the SKU isn't
 *     currently flagged for re-slotting (most SKUs won't be — this is NOT a
 *     full ABC classification of every item).
 *
 * There is no synthesized/hashed fallback for any of the above — this
 * replaces the prior deterministic-hash mock derivation.
 */
import type { ProductResponse } from '@/types/product';
import type { EntityTone } from '@/components/master-detail/tones';
import type { ItemStock } from './use-item-stock';
import type { SkuForecast } from '@/pages/insights/forecasting-api';
import type { ReSlotSuggestion } from '@/pages/insights/slotting-api';

export interface ItemView {
  id: number;
  sku: string;
  name: string;
  category: string;
  uom: string;
  barcode: string;
  pack: string;
  weight: string;
  dims: string;
  lotTracked: string;

  stock: ItemStock | null;
  forecast: SkuForecast | null;
  slotting: ReSlotSuggestion | null;
  cls: 'A' | 'B' | 'C' | null;
}

const ABC_CLASSES = new Set(['A', 'B', 'C']);

/** Narrow a slotting `abcClass` string to the known letter set; anything else (or absent) is `null`. */
function narrowAbcClass(abcClass: string | undefined): 'A' | 'B' | 'C' | null {
  if (!abcClass) return null;
  const upper = abcClass.toUpperCase();
  return ABC_CLASSES.has(upper) ? (upper as 'A' | 'B' | 'C') : null;
}

/**
 * Build an item view-model from a real product plus optional real
 * aggregates. Identity is always populated from `p`; stock/forecast/slotting
 * default to `null` when not supplied (absent data, not zero/synthesized
 * data).
 */
export function buildItemView(
  p: ProductResponse,
  stock?: ItemStock,
  forecast?: SkuForecast,
  slotting?: ReSlotSuggestion,
): ItemView {
  const barcode = p.numbers.find((n) => n.number)?.number ?? '—';
  // Pack representation: the lowest packingLevel > 0 unit (e.g. the case above
  // the base unit); fall back to the first unit holding more than one base unit.
  const leveled = p.packagingUnits
    .filter((u) => u.packingLevel > 0)
    .sort((a, b) => a.packingLevel - b.packingLevel);
  const packagingUnit = leveled[0] ?? p.packagingUnits.find((u) => u.amount > 1);
  const pack = packagingUnit ? `${packagingUnit.amount} × ${packagingUnit.name}` : '—';
  const weight = p.weight != null ? `${p.weight} kg` : '—';
  const dims =
    p.height != null && p.width != null && p.depth != null
      ? `${p.width}×${p.depth}×${p.height} cm`
      : '—';
  const lotTracked = p.lotMandatory ? (p.bestBeforeMandatory ? 'Yes · FEFO' : 'Yes · FIFO') : 'No';

  return {
    id: p.id,
    sku: p.number,
    name: p.name,
    category: p.tradeGroup ?? p.description ?? 'Uncategorized',
    uom: p.itemUnit?.name ?? 'each',
    barcode,
    pack,
    weight,
    dims,
    lotTracked,
    stock: stock ?? null,
    forecast: forecast ?? null,
    slotting: slotting ?? null,
    cls: narrowAbcClass(slotting?.abcClass),
  };
}

/** Available count vs. reorder point → text color (red out / amber below / neutral), as a CSS var string. */
export function availColor(available: number | null, reorderPoint: number | null): string {
  if (available == null) return 'var(--muted-foreground)';
  if (available <= 0) return 'var(--danger)';
  if (reorderPoint != null && available < reorderPoint) return 'var(--warning-foreground)';
  return 'var(--foreground)';
}

/** Available count vs. reorder point → row rail tone (grey when no stock data yet). */
export function availTone(available: number | null, reorderPoint: number | null): EntityTone {
  if (available == null) return 'grey';
  if (available <= 0) return 'red';
  if (reorderPoint != null && available < reorderPoint) return 'amber';
  return 'lime';
}
