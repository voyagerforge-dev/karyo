import { ArrowDown, ArrowUp, Box } from 'lucide-react';
import { toast } from 'sonner';
import { AttributeGrid } from '@/components/control/attribute-grid';
import { ClassChip } from '@/components/control/class-chip';
import { DemandSparkbars } from '@/components/control/demand-sparkbars';
import { SectionCard } from '@/components/control/section-card';
import { StackedBar } from '@/components/control/stacked-bar';
import { StatusPill } from '@/components/control/status-pill';
import type { EntityTone } from '@/components/master-detail/tones';
import { getStockStateVariant, humanizeStateName } from '@/lib/state-variants';
import type { ItemView } from './item-model';

/** Neutral "still resolving" placeholder — shown while a block's data is in flight. */
function BlockLoading() {
  return <p className="text-[12.5px] text-muted-foreground/70">Loading…</p>;
}

/** Shared stock/order state badge variant, remapped onto the Control tone palette used by StatusPill. */
function storedRowTone(state: string): EntityTone {
  switch (getStockStateVariant(state)) {
    case 'success':
      return 'lime';
    case 'warning':
      return 'amber';
    case 'error':
      return 'red';
    default:
      return 'grey';
  }
}

/** The right-pane item workspace: header → stock position → demand/attributes → where-stored → inbound/open-demand. */
export function ItemDetail({
  item,
  forecastEntitled,
  slottingEntitled,
  stockLoading,
  forecastLoading,
  slottingLoading,
  onEdit,
}: {
  item: ItemView;
  forecastEntitled: boolean;
  slottingEntitled: boolean;
  stockLoading: boolean;
  forecastLoading: boolean;
  slottingLoading: boolean;
  /** Opens the item form Sheet pre-filled with this item's product (items-page resolves the ProductResponse). */
  onEdit: () => void;
}) {
  const { stock, forecast } = item;
  // Re-slot recommendation is only used as a tooltip on the header class chip
  // (the design has no standalone Slotting section on this pane) — guarded
  // the same way the list/model layers guard it: licensed, settled, present.
  const reslot = !slottingLoading && slottingEntitled && item.slotting ? item.slotting : null;
  const hasForecast = forecastEntitled && !!forecast;
  const coverDays =
    hasForecast && forecast.avgDailyDemand > 0
      ? Math.round(forecast.currentOnHand / forecast.avgDailyDemand)
      : null;

  return (
    <div className="flex flex-col gap-4 pr-1">
      {/* header */}
      <div className="flex items-start gap-[18px]">
        {/* product image slot — placeholder */}
        <div className="flex size-[74px] flex-none items-center justify-center rounded-[14px] border border-border bg-background text-muted-foreground/50">
          <Box className="size-9" strokeWidth={1.4} />
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2.5">
            <h1 className="font-display text-[22px] text-foreground">{item.name}</h1>
            <span title={reslot ? `${reslot.direction} — ${reslot.reason}` : undefined}>
              <ClassChip cls={item.cls} size="md" />
            </span>
          </div>
          <p className="numeric mt-1.5 text-[13px] text-foreground/70">
            {item.sku} · {item.category} · {item.uom}
          </p>
        </div>
        <div className="flex flex-none gap-2">
          <button
            type="button"
            onClick={() =>
              toast('Replenish', { description: `Replenishment for ${item.sku} is not wired yet.` })
            }
            className="h-9 rounded-[9px] bg-primary px-3.5 text-[13px] font-bold text-primary-foreground transition-opacity hover:opacity-90"
          >
            Replenish
          </button>
          <button
            type="button"
            onClick={onEdit}
            className="h-9 rounded-[9px] border border-border bg-card px-[13px] text-[13px] font-medium text-foreground/85 hover:bg-accent"
          >
            Edit
          </button>
        </div>
      </div>

      {/* stock position */}
      <section className="rounded-2xl border border-border bg-card p-5">
        <div className="mb-3.5 flex items-center justify-between">
          <h2 className="text-sm font-semibold text-foreground">Stock position</h2>
          {stock && (
            <span className="numeric text-[11px] text-muted-foreground">
              across {stock.locations.length} location{stock.locations.length === 1 ? '' : 's'}
            </span>
          )}
        </div>
        {stockLoading ? (
          <BlockLoading />
        ) : stock ? (
          <>
            <StackedBar
              segments={[
                { value: stock.available, color: 'var(--acc-color)', label: 'Available' },
                { value: stock.reserved, color: 'var(--warning-foreground)', label: 'Allocated' },
                { value: stock.held, color: 'var(--destructive)', label: 'Held' },
              ]}
            />
            <div className="mt-3 flex flex-wrap items-center gap-x-6 gap-y-2">
              <Legend swatch="var(--acc-color)" label="Available" value={stock.available} />
              <Legend swatch="var(--warning-foreground)" label="Allocated" value={stock.reserved} />
              <Legend swatch="var(--destructive)" label="Held" value={stock.held} />
              <div className="ml-auto flex items-center gap-2">
                <span className="text-[12.5px] text-muted-foreground">On hand</span>
                <span className="numeric text-[15px] font-bold text-foreground">
                  {stock.onHand.toLocaleString()}
                </span>
              </div>
            </div>
          </>
        ) : (
          <p className="text-[12.5px] text-muted-foreground/70">No stock units on hand for this item.</p>
        )}
      </section>

      {/* demand + attributes */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <section className="rounded-2xl border border-border bg-card p-5">
          <div className="mb-[18px] flex items-start justify-between">
            <div>
              <h2 className="text-sm font-semibold text-foreground">Demand · 30 days</h2>
              {hasForecast && (
                <p className="numeric mt-1 text-[11px] text-muted-foreground/80">
                  {forecast.avgDailyDemand.toLocaleString()} units/day avg
                </p>
              )}
            </div>
            {hasForecast && coverDays != null && (
              <span className="numeric rounded-[7px] bg-signal/10 px-[9px] py-[3px] text-[11px] font-bold text-signal">
                {coverDays}d cover
              </span>
            )}
          </div>
          {/* Daily demand series has no backend source yet — the sparkbar
              chart is always the honest "No demand history yet" empty-state,
              even when the summary numbers above are real. */}
          {forecastLoading ? <BlockLoading /> : <DemandSparkbars bars={null} />}
        </section>

        <section className="rounded-2xl border border-border bg-card p-5">
          <h2 className="mb-4 text-sm font-semibold text-foreground">Attributes</h2>
          <AttributeGrid
            items={[
              { label: 'Barcode', value: item.barcode },
              { label: 'Pack size', value: item.pack },
              { label: 'Weight', value: item.weight },
              { label: 'Dimensions', value: item.dims },
              {
                label: 'Reorder point',
                value: hasForecast ? forecast.suggestedReorderPoint.toLocaleString() : null,
              },
              { label: 'Lot tracked', value: item.lotTracked },
            ]}
          />
        </section>
      </div>

      {/* where it's stored */}
      <SectionCard
        title="Where it's stored"
        action={<span className="numeric text-[10.5px] text-muted-foreground/70">LOCATION · UNIT LOAD · QTY</span>}
        noPad
      >
        {stockLoading ? (
          <p className="px-[18px] py-4 text-[12.5px] text-muted-foreground/70">Loading…</p>
        ) : !stock || stock.locations.length === 0 ? (
          <p className="px-[18px] py-4 text-[12.5px] text-muted-foreground/70">No stock units on hand for this item.</p>
        ) : (
          stock.locations.map((s, i) => (
            <div
              key={i}
              className="grid grid-cols-[130px_1fr_90px_96px] items-center gap-3 border-b border-border px-[18px] py-3 last:border-b-0"
            >
              <span className="numeric text-[13px] font-semibold text-foreground">{s.location}</span>
              <div className="flex min-w-0 items-center gap-2">
                <span className="numeric truncate text-[12.5px] text-muted-foreground">{s.lpn}</span>
                {/* location type (LPN-tracked vs loose) isn't in ItemStock yet — omit the tag rather than assert LPN; wire when a location-type field exists. */}
              </div>
              <span className="numeric text-right text-[13px] font-bold text-foreground">
                {s.amount.toLocaleString()}
              </span>
              <div className="text-right">
                <StatusPill label={humanizeStateName(s.state)} tone={storedRowTone(s.state)} />
              </div>
            </div>
          ))
        )}
      </SectionCard>

      {/* inbound / open demand — no per-item source yet; honest "None" */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <section className="rounded-2xl border border-border bg-card p-[18px]">
          <div className="mb-3.5 flex items-center gap-2">
            <ArrowDown className="size-[15px] flex-none" style={{ color: 'var(--info)' }} strokeWidth={2} />
            <h2 className="text-sm font-semibold text-foreground">Inbound</h2>
          </div>
          <p className="text-[12.5px] text-muted-foreground/70">None</p>
        </section>
        <section className="rounded-2xl border border-border bg-card p-[18px]">
          <div className="mb-3.5 flex items-center gap-2">
            <ArrowUp className="size-[15px] flex-none" style={{ color: 'var(--acc-color)' }} strokeWidth={2} />
            <h2 className="text-sm font-semibold text-foreground">Open demand</h2>
          </div>
          <p className="text-[12.5px] text-muted-foreground/70">None</p>
        </section>
      </div>
    </div>
  );
}

function Legend({ swatch, label, value }: { swatch: string; label: string; value: number }) {
  return (
    <div className="flex items-center gap-2">
      <span className="size-2.5 rounded-[3px]" style={{ background: swatch }} />
      <span className="text-[12.5px] text-muted-foreground">{label}</span>
      <span className="numeric text-[13px] font-bold text-foreground">{value.toLocaleString()}</span>
    </div>
  );
}
