import { Lock } from 'lucide-react';
import { cn } from '@/lib/utils';
import type { ExceptionRow, ExceptionSeverity } from '@/pages/home/ops/ops-adapters';

const SEVERITY_BAR: Record<ExceptionSeverity, string> = {
  danger: 'bg-destructive',
  warning: 'bg-warning-foreground',
  info: 'bg-info',
};

interface ExceptionsCardProps {
  exceptions: ExceptionRow[];
  /** False on an unlicensed instance — the monitors engine that backs this list is gated. */
  monitorsEntitled: boolean;
  /**
   * True while the monitors license entitlement is still loading, or while an
   * entitled tenant's alerts query is in flight. While true, neither the
   * locked panel nor the empty state is shown — both would be a false
   * absence before the license/query has actually resolved.
   */
  monitorsLoading: boolean;
}

/**
 * Exceptions list: a header with a live count pill, then one row per open
 * exception (a firing `karyo-monitors` alert) with a severity left-bar,
 * type/detail, and age. Shows a small locked panel when `monitors` isn't
 * entitled, and a quiet empty state when it is entitled but nothing is firing.
 */
export function ExceptionsCard({ exceptions, monitorsEntitled, monitorsLoading }: ExceptionsCardProps) {
  if (monitorsLoading) {
    return (
      <section
        data-testid="exceptions-loading"
        className="rounded-2xl border border-border bg-card p-[var(--cpad,22px)]"
      >
        <h2 className="m-0 text-[15px] font-semibold text-foreground">Exceptions</h2>
        <p className="mt-4 text-[12px] text-muted-foreground">Loading…</p>
      </section>
    );
  }

  if (!monitorsEntitled) {
    return (
      <section
        data-testid="exceptions-locked"
        className="flex flex-col items-center justify-center gap-2 rounded-2xl border border-border bg-card px-6 py-10 text-center"
      >
        <Lock className="size-4 text-muted-foreground" strokeWidth={2} />
        <h2 className="m-0 text-[13px] font-semibold text-foreground">Exceptions</h2>
        <p className="m-0 text-[11.5px] text-muted-foreground">
          Requires the Monitors add-on.
        </p>
      </section>
    );
  }

  return (
    <section className="rounded-2xl border border-border bg-card p-[var(--cpad,22px)]">
      <div className="mb-[18px] flex items-center gap-[9px]">
        <h2 className="m-0 text-[15px] font-semibold text-foreground">Exceptions</h2>
        <span className="numeric rounded-[6px] bg-destructive/12 px-[7px] py-0.5 text-[10px] font-bold text-destructive">
          {exceptions.length}
        </span>
      </div>

      {exceptions.length === 0 ? (
        <p className="text-[12px] text-muted-foreground">No open exceptions.</p>
      ) : (
        <div className="flex flex-col">
          {exceptions.map((e, i) => (
            <div
              key={`${e.type}-${i}`}
              className={cn(
                'flex items-center gap-3 py-[11px]',
                i < exceptions.length - 1 && 'border-b border-border',
              )}
            >
              <span className={cn('h-[30px] w-[3px] flex-none rounded-sm', SEVERITY_BAR[e.severity])} />
              <div className="min-w-0 flex-1">
                <div className="numeric text-[12px] font-bold text-foreground">{e.type}</div>
                <div className="mt-0.5 text-[11.5px] text-muted-foreground">{e.detail}</div>
              </div>
              <span className="numeric text-[11px] text-muted-foreground/70">{e.age}</span>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
