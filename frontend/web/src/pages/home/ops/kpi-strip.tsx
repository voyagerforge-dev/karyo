import { cn } from '@/lib/utils';
import type { DeltaTone, KpiCell } from '@/pages/home/ops/ops-adapters';

const STROKE: Record<DeltaTone, string> = {
  up: 'var(--acc-color)',
  warning: 'var(--warning-foreground)',
  danger: 'var(--destructive)',
};

const DELTA_CLASS: Record<DeltaTone, string> = {
  up: 'text-primary',
  warning: 'text-warning-foreground',
  danger: 'text-destructive',
};

interface KpiStripProps {
  kpis: KpiCell[];
}

/**
 * The KPI strip — a single rounded bar divided into equal cells by right
 * borders. Each cell: micro-label → mono value (+ dimmed unit) + inline
 * 58×24 sparkline → delta arrow + context.
 */
export function KpiStrip({ kpis }: KpiStripProps) {
  if (kpis.length === 0) {
    return (
      <div className="mb-[var(--secmb,20px)] flex items-center justify-center rounded-2xl border border-border bg-card p-6 text-[12px] text-muted-foreground">
        No KPI data yet.
      </div>
    );
  }

  return (
    <div className="mb-[var(--secmb,20px)] flex flex-col overflow-hidden rounded-2xl border border-border bg-card sm:flex-row">
      {kpis.map((kpi, i) => (
        <div
          key={kpi.label}
          className={cn(
            'flex-1 p-[var(--kpad,16px_18px)]',
            i < kpis.length - 1 && 'border-b border-border sm:border-b-0 sm:border-r',
          )}
        >
          <div className="text-[10px] font-semibold uppercase tracking-[0.12em] text-muted-foreground/70">
            {kpi.label}
          </div>
          <div className="mt-2.5 flex items-end justify-between">
            <span
              className={cn(
                'numeric text-[25px] font-bold tracking-[-0.02em]',
                kpi.valueDanger ? 'text-destructive' : 'text-foreground',
              )}
            >
              {kpi.value}
              {kpi.unit ? <span className="text-[14px] text-muted-foreground/80">{kpi.unit}</span> : null}
            </span>
            <svg
              width="58"
              height="24"
              viewBox="0 0 58 24"
              fill="none"
              preserveAspectRatio="none"
              aria-hidden
            >
              <polyline
                points={kpi.points}
                stroke={STROKE[kpi.tone]}
                strokeWidth="1.8"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          </div>
          <div className="mt-[9px] flex items-center gap-2">
            <span className={cn('numeric text-[11px] font-bold', DELTA_CLASS[kpi.tone])}>
              {kpi.deltaArrow} {kpi.deltaText}
            </span>
            <span className="text-[11px] text-muted-foreground/70">{kpi.context}</span>
          </div>
        </div>
      ))}
    </div>
  );
}
