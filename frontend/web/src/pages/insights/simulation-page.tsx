import { Lock } from 'lucide-react';
import { useLicense } from '@/features/license/use-license';
import { useSimulation } from '@/pages/insights/use-simulation';
import { Badge } from '@/components/ui/badge';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { cn } from '@/lib/utils';
import type { ReorderSimResponse } from '@/pages/insights/simulation-api';

/**
 * Insights → Simulation (v1.7d): per-SKU reorder what-if backtest computed by the
 * `karyo-simulation` module (`GET /api/v1/simulations/reorder`). Replays each SKU's
 * historical demand through a naive vs. safety-stock reorder policy and shows the
 * quantified before/after benefit.
 *
 * Gated behind the `simulation` paid-license entitlement
 * (`useLicense().isEntitled('simulation')`). The gate is checked BEFORE `useSimulation`
 * is invoked, so an unentitled tenant never calls the endpoint (which would 403 anyway).
 */
function LockedSimulationPanel() {
  return (
    <div
      data-testid="simulation-locked"
      className="flex flex-col items-center justify-center gap-3 rounded-2xl border border-border bg-card px-8 py-20 text-center"
    >
      <div className="flex size-12 items-center justify-center rounded-full bg-[rgba(199,242,78,0.1)]">
        <Lock className="size-5 text-primary" strokeWidth={2} />
      </div>
      <h1 className="font-display m-0 text-[20px] font-bold tracking-[-0.02em] text-foreground">
        Simulation is a paid add-on
      </h1>
      <p className="m-0 max-w-md text-[13px] text-muted-foreground">
        What-if reorder backtests — replaying your demand history to quantify the stockout-days
        and inventory a safety-stock policy would save — contact your account team to enable
        Simulation for this tenant.
      </p>
    </div>
  );
}

const pct = (n: number) => `${(n * 100).toFixed(1)}%`;
const num = (n: number) => n.toFixed(1);

function SummaryBanner({ summary }: { summary: ReorderSimResponse['summary'] }) {
  const cards = [
    { label: 'SKUs simulated', value: String(summary.skusSimulated) },
    { label: 'Stockout-days avoided', value: String(summary.totalStockoutDaysAvoided) },
    { label: 'Avg fill-rate lift', value: pct(summary.avgFillRateDelta) },
    { label: 'Avg on-hand change', value: num(summary.totalAvgOnHandDelta) },
  ];
  return (
    <div className="mb-5 grid grid-cols-2 gap-3 sm:grid-cols-4">
      {cards.map((c) => (
        <div key={c.label} className="rounded-2xl border border-border bg-card p-4">
          <div className="text-[12px] text-muted-foreground">{c.label}</div>
          <div className="numeric mt-1 text-[22px] font-bold tracking-[-0.02em] text-foreground">
            {c.value}
          </div>
        </div>
      ))}
    </div>
  );
}

function SimulationBody() {
  const { data, isLoading, isError } = useSimulation();

  if (isLoading) {
    return <p className="text-[13px] text-muted-foreground">Loading…</p>;
  }
  if (isError) {
    return (
      <div className="rounded-2xl border border-border bg-card p-4 text-[13px] text-destructive">
        Couldn&apos;t load reorder simulations.
      </div>
    );
  }
  if (!data || data.rows.length === 0) {
    return (
      <div className="rounded-2xl border border-border bg-card p-8 text-center text-[13px] text-muted-foreground">
        No reorder simulations yet — demand history is still building.
      </div>
    );
  }

  return (
    <>
      <SummaryBanner summary={data.summary} />
      <div className="rounded-2xl border border-border bg-card">
        <Table data-testid="simulation-table">
          <TableHeader>
            <TableRow>
              <TableHead>SKU</TableHead>
              <TableHead>Confidence</TableHead>
              <TableHead className="text-right">Stockout-days (base → sugg)</TableHead>
              <TableHead className="text-right">Days avoided</TableHead>
              <TableHead className="text-right">Fill-rate (base → sugg)</TableHead>
              <TableHead className="text-right">Avg on-hand (base → sugg)</TableHead>
              <TableHead className="text-right">Reorder point (base → sugg)</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.rows.map((row) => (
              <TableRow
                key={row.sku}
                data-testid={`simulation-row-${row.sku}`}
                className={cn(row.stockoutDaysAvoided > 0 && 'bg-primary/5')}
              >
                <TableCell className="font-medium">{row.sku}</TableCell>
                <TableCell>{row.confidence}</TableCell>
                <TableCell className="numeric text-right">
                  {row.baselineStockoutDays} → {row.suggestedStockoutDays}
                </TableCell>
                <TableCell className="text-right">
                  {row.stockoutDaysAvoided > 0 ? (
                    <Badge variant="success">{row.stockoutDaysAvoided}</Badge>
                  ) : (
                    <span className="numeric text-muted-foreground">{row.stockoutDaysAvoided}</span>
                  )}
                </TableCell>
                <TableCell className="numeric text-right">
                  {pct(row.baselineFillRate)} → {pct(row.suggestedFillRate)}
                </TableCell>
                <TableCell className="numeric text-right">
                  {num(row.baselineAvgOnHand)} → {num(row.suggestedAvgOnHand)}
                </TableCell>
                <TableCell className="numeric text-right">
                  {row.baselineReorderPoint} → {row.suggestedReorderPoint}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </>
  );
}

export function SimulationPage() {
  const license = useLicense();

  let body: React.ReactNode;
  if (license.isLoading) {
    body = <p className="text-[13px] text-muted-foreground">Loading…</p>;
  } else if (!license.isEntitled('simulation')) {
    body = <LockedSimulationPanel />;
  } else {
    body = <SimulationBody />;
  }

  return (
    <div data-testid="simulation-page">
      <div className="mb-5">
        <h1 className="font-display text-2xl font-bold tracking-[-0.02em] text-foreground">
          Simulation
        </h1>
        <p className="mt-1 text-[13px] text-muted-foreground">
          What-if reorder backtests — the stockout-days and inventory a safety-stock policy would save.
        </p>
      </div>
      {body}
    </div>
  );
}
