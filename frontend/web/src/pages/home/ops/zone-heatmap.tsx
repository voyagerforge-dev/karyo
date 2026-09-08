import { cn } from '@/lib/utils';
import type { ZoneField } from '@/pages/home/ops/ops-adapters';

interface ZoneHeatmapProps {
  /** Real occupancy field from `useOpsData` (`occupancyToZoneField`), or `null` while loading. */
  field: ZoneField | null;
}

/**
 * Zone heatmap: one 20px cell per REAL storage location (colored by its
 * actual occupancy state), laid out in a responsive auto-fill grid — there is
 * no fixed zone/aisle geometry to assume, so (unlike the old 6×12 mock grid
 * with row labels A–F) the cell count varies with the facility. A legend row
 * sits below.
 */
export function ZoneHeatmap({ field }: ZoneHeatmapProps) {
  if (!field || field.cells.length === 0) {
    return (
      <section className="rounded-2xl border border-border bg-card p-[var(--cpad,22px)]">
        <h2 className="m-0 text-[15px] font-semibold text-foreground">Zone occupancy</h2>
        <p className="mt-4 text-[12px] text-muted-foreground">No occupancy data yet.</p>
      </section>
    );
  }

  return (
    <section className="rounded-2xl border border-border bg-card p-[var(--cpad,22px)]">
      <div className="mb-[18px] flex items-center justify-between">
        <div>
          <h2 className="m-0 text-[15px] font-semibold text-foreground">{field.title}</h2>
          <p className="numeric m-0 mt-1 text-[11px] tracking-[0.04em] text-muted-foreground/80">{field.sub}</p>
        </div>
        <span
          className={cn(
            'numeric rounded-[7px] px-[9px] py-1 text-[11px] font-bold',
            field.badgeTone === 'accent'
              ? 'border border-[var(--acc-line)] bg-[var(--acc-soft)] text-primary'
              : 'border border-destructive/30 bg-destructive/12 text-destructive',
          )}
        >
          {field.badgeText}
        </span>
      </div>

      {/* Auto-fill responsive field — one cell per real location. */}
      <div
        className="grid gap-1.5"
        style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(20px, 1fr))', gridAutoRows: '20px' }}
      >
        {field.cells.map((cell, i) => (
          <div
            key={i}
            title={cell.title}
            className="rounded-[4px] border border-black/20"
            style={{ background: cell.color }}
          />
        ))}
      </div>

      <div className="mt-4 flex items-center gap-4 border-t border-border pt-3.5">
        <span className="numeric text-[10px] tracking-[0.08em] text-muted-foreground/70">
          {field.legendUnit}
        </span>
        {field.legend.map((l) => (
          <div key={l.label} className="flex items-center gap-1.5">
            <span
              className="h-[11px] w-[11px] rounded-[3px]"
              style={{ background: l.color }}
            />
            <span className="text-[11px] text-muted-foreground">{l.label}</span>
          </div>
        ))}
      </div>
    </section>
  );
}
