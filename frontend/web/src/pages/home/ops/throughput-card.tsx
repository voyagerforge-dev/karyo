import { cn } from '@/lib/utils';
import type { ThroughputView } from '@/pages/home/ops/ops-adapters';

interface ThroughputCardProps {
  view: ThroughputView | null;
}

/**
 * Throughput card: header + mono PEAK/AVG sub, over a 158px daily bar chart.
 * Peak bar paints solid lime, others lime-dim.
 *
 * The prototype's Shift/Day (hourly) toggle is gone — there is no real
 * hourly backing series, only the daily `chart.outbound` window from
 * `GET /insights/kpis`. The range selector living in `OperationsControl`
 * maps to that real `?range=` window instead.
 */
export function ThroughputCard({ view }: ThroughputCardProps) {
  if (!view || view.bars.length === 0) {
    return (
      <section className="rounded-2xl border border-border bg-card p-[var(--cpad,22px)]">
        <h2 className="m-0 text-[15px] font-semibold text-foreground">Throughput</h2>
        <p className="mt-4 text-[12px] text-muted-foreground">No throughput data yet.</p>
      </section>
    );
  }

  return (
    <section className="rounded-2xl border border-border bg-card p-[var(--cpad,22px)]">
      <div className="mb-6">
        <h2 className="m-0 text-[15px] font-semibold text-foreground">Throughput</h2>
        <p className="numeric m-0 mt-1 text-[11px] tracking-[0.04em] text-muted-foreground/80">
          {view.peakText} · {view.avgText}
        </p>
      </div>

      <div className="relative flex h-[158px] items-end gap-2.5">
        {view.bars.map((b, i) => (
          <div
            key={`${b.label}-${i}`}
            className="flex h-full flex-1 flex-col items-center justify-end gap-2"
          >
            <div
              className="w-full max-w-[32px] rounded-[5px_5px_2px_2px]"
              style={{
                height: `${b.pct}%`,
                background: b.isPeak ? 'var(--acc-color)' : 'var(--acc-dim)',
              }}
            />
            <span
              className={cn(
                'numeric text-[10px]',
                b.isPeak ? 'text-primary' : 'text-muted-foreground/70',
              )}
            >
              {b.label}
            </span>
          </div>
        ))}
      </div>
    </section>
  );
}
