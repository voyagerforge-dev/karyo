import { useState } from 'react';
import { cn } from '@/lib/utils';
import { useOpsData } from '@/pages/home/ops/use-ops-data';
import { useControlPrefsContext } from '@/pages/home/ops/control-prefs-context';
import { useClock } from '@/hooks/use-clock';
import type { Density } from '@/pages/home/ops/use-density';
import { KpiStrip } from '@/pages/home/ops/kpi-strip';
import { ThroughputCard } from '@/pages/home/ops/throughput-card';
import { ZoneHeatmap } from '@/pages/home/ops/zone-heatmap';
import { ExceptionsCard } from '@/pages/home/ops/exceptions-card';
import { SampleDataCard } from '@/features/sample-data/sample-data-card';
import { useDemoEnabled } from '@/features/sample-data/use-demo-enabled';
import { RANGES, type ReportRange } from '@/types/insights';

/**
 * Operations Control — the floor manager's real-time command center (Screen 1).
 *
 * Rewired to real data (demo-hardening Task 3): 4 tiles, all backed by real
 * endpoints via `useOpsData` (`GET /insights/kpis`, `GET /insights/occupancy`,
 * `GET /alerts`) — KPI strip, Throughput, Zone occupancy, Exceptions. The
 * mock-only tiles (Copilot recs, Active waves, Dock doors, Picker throughput)
 * and their `use-ops-console` data source are gone; those engines don't exist
 * yet (wave planning / yard management / labor reporting / AI copilot are
 * future milestones).
 *
 * `SampleDataCard` (re-remounted 2026-07-21, see that file's header) sits
 * above the KPI strip -- the same spot the pre-redesign dashboard gave it.
 * Mounted only when `useDemoEnabled()` resolves true (Task 11,
 * defect-burndown, 2026-07-31): the card was previously unconditional, a
 * dead surface whose Load click 500'd (and toasted forever) on any
 * deployment with `KARYO_DEMO` off.
 */
const DENSITIES: Array<{ value: Density; label: string }> = [
  { value: 'comfortable', label: 'Comfortable' },
  { value: 'command', label: 'Command' },
];

export function OperationsControl() {
  const [range, setRange] = useState<ReportRange>('7D');
  const ops = useOpsData(range);
  const clock = useClock();
  const { density, set } = useControlPrefsContext();
  const demoEnabled = useDemoEnabled();

  return (
    // data-density lives on <html> (set by ControlPrefsProvider) so the whole
    // surface shares it; the wrapper just tags the screen for tests.
    <div data-testid="dashboard-home">
      {/* Heading row */}
      <div className="mb-5 flex items-end justify-between">
        <div>
          <h1 className="font-display m-0 text-[22px] text-foreground">Operations Control</h1>
          <p className="numeric m-0 mt-1 text-[10.5px] tracking-[0.06em] text-muted-foreground/70">
            REAL-TIME FLOOR · {clock || '—'}
          </p>
        </div>
        <div className="flex items-center gap-2.5">
          {/* Range selector — drives the real ?range= window on the KPI/throughput query. */}
          <div className="numeric flex gap-[3px] rounded-[10px] border border-border bg-background p-[3px]">
            {RANGES.map((r) => (
              <button
                key={r}
                type="button"
                onClick={() => setRange(r)}
                className={cn(
                  'rounded-[8px] px-3 py-1.5 text-[11.5px] tracking-[0.04em] transition-colors',
                  range === r
                    ? 'bg-primary font-bold text-primary-foreground'
                    : 'font-medium text-muted-foreground hover:text-foreground',
                )}
              >
                {r}
              </button>
            ))}
          </div>
          {/* Density toggle (v2 tweakable prop) — still drives the spacing vars
              (--cpad/--kpad/--cgap/--secmb) the 4 kept tiles use. */}
          <div className="numeric flex gap-[3px] rounded-[10px] border border-border bg-background p-[3px]">
            {DENSITIES.map((d) => (
              <button
                key={d.value}
                type="button"
                onClick={() => set('density', d.value)}
                className={cn(
                  'rounded-[8px] px-3 py-1.5 text-[11.5px] tracking-[0.04em] transition-colors',
                  density === d.value
                    ? 'bg-primary font-bold text-primary-foreground'
                    : 'font-medium text-muted-foreground hover:text-foreground',
                )}
              >
                {d.label}
              </button>
            ))}
          </div>
        </div>
      </div>

      {demoEnabled && <SampleDataCard />}

      <KpiStrip kpis={ops.kpis} />

      <div className="grid grid-cols-1 gap-[var(--cgap,20px)] lg:grid-cols-3">
        <ThroughputCard view={ops.throughput} />
        <ZoneHeatmap field={ops.zone} />
        <ExceptionsCard
          exceptions={ops.exceptions}
          monitorsEntitled={ops.monitorsEntitled}
          monitorsLoading={ops.monitorsLoading}
        />
      </div>
    </div>
  );
}
