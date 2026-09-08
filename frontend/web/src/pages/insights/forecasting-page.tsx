import { Lock } from 'lucide-react';
import { useLicense } from '@/features/license/use-license';
import { useForecasts } from '@/pages/insights/use-forecasts';
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

/**
 * Insights → Forecasting (v1.7b): per-SKU reorder suggestions computed by the
 * `karyo-forecasting` module (`GET /api/v1/forecasts`).
 *
 * Gated behind the `forecasting` paid-license entitlement
 * (`useLicense().isEntitled('forecasting')`, backed by `GET /api/v1/license`
 * — the gate-discovery endpoint that is never itself license-gated). The
 * gate is checked BEFORE `useForecasts` is invoked, so an unentitled tenant
 * never calls `/api/v1/forecasts` (which would 403 anyway).
 */
function LockedForecastingPanel() {
  return (
    <div
      data-testid="forecasting-locked"
      className="flex flex-col items-center justify-center gap-3 rounded-2xl border border-border bg-card px-8 py-20 text-center"
    >
      <div className="flex size-12 items-center justify-center rounded-full bg-[rgba(199,242,78,0.1)]">
        <Lock className="size-5 text-primary" strokeWidth={2} />
      </div>
      <h1 className="font-display m-0 text-[20px] font-bold tracking-[-0.02em] text-foreground">
        Demand forecasting is a paid add-on
      </h1>
      <p className="m-0 max-w-md text-[13px] text-muted-foreground">
        Per-SKU demand forecasts and reorder-point suggestions, computed from your pick and
        receiving history — contact your account team to enable Forecasting for this tenant.
      </p>
    </div>
  );
}

function fmt(n: number): string {
  return Number.isInteger(n) ? String(n) : n.toFixed(1);
}

function ForecastingTable() {
  const { data, isLoading, isError } = useForecasts();

  if (isLoading) {
    return <p className="text-[13px] text-muted-foreground">Loading…</p>;
  }
  if (isError) {
    return (
      <div className="rounded-2xl border border-border bg-card p-4 text-[13px] text-destructive">
        Couldn&apos;t load forecasts.
      </div>
    );
  }
  if (!data || data.length === 0) {
    return (
      <div className="rounded-2xl border border-border bg-card p-8 text-center text-[13px] text-muted-foreground">
        No forecasts yet — demand history is still building.
      </div>
    );
  }

  return (
    <div className="rounded-2xl border border-border bg-card">
      <Table data-testid="forecasts-table">
        <TableHeader>
          <TableRow>
            <TableHead>SKU</TableHead>
            <TableHead className="text-right">Avg daily demand</TableHead>
            <TableHead className="text-right">Forecast (next N days)</TableHead>
            <TableHead className="text-right">On hand</TableHead>
            <TableHead className="text-right">Suggested reorder point</TableHead>
            <TableHead className="text-right">Suggested reorder qty</TableHead>
            <TableHead>Confidence</TableHead>
            <TableHead />
          </TableRow>
        </TableHeader>
        <TableBody>
          {data.map((row) => (
            <TableRow
              key={row.sku}
              data-testid={`forecast-row-${row.sku}`}
              className={cn(row.belowReorderPoint && 'bg-destructive/5')}
            >
              <TableCell className="font-medium">{row.sku}</TableCell>
              <TableCell className="numeric text-right">{fmt(row.avgDailyDemand)}</TableCell>
              <TableCell className="numeric text-right">{fmt(row.forecastNextNDays)}</TableCell>
              <TableCell className="numeric text-right">{row.currentOnHand}</TableCell>
              <TableCell className="numeric text-right">{row.suggestedReorderPoint}</TableCell>
              <TableCell className="numeric text-right">{row.suggestedReorderQty}</TableCell>
              <TableCell>{row.confidence}</TableCell>
              <TableCell>
                {row.belowReorderPoint && <Badge variant="warning">Reorder now</Badge>}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

export function ForecastingPage() {
  const license = useLicense();

  let body: React.ReactNode;
  if (license.isLoading) {
    body = <p className="text-[13px] text-muted-foreground">Loading…</p>;
  } else if (!license.isEntitled('forecasting')) {
    body = <LockedForecastingPanel />;
  } else {
    body = <ForecastingTable />;
  }

  return (
    <div data-testid="forecasting-page">
      <div className="mb-5">
        <h1 className="font-display text-2xl font-bold tracking-[-0.02em] text-foreground">
          Forecasting
        </h1>
        <p className="mt-1 text-[13px] text-muted-foreground">
          Per-SKU demand forecasts and reorder-point suggestions.
        </p>
      </div>
      {body}
    </div>
  );
}
