import { useMemo, useState } from 'react';
import { Download, Plus, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { cn } from '@/lib/utils';
import { useKpis } from '@/features/insights/use-kpis';
import { useVolumeByCategory } from '@/features/insights/use-volume-by-category';
import {
  useReportDefinitions,
  useCreateReportDefinition,
  useDeleteReportDefinition,
} from '@/features/reports/use-report-definitions';
import { usePermissions } from '@/hooks/use-permissions';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';
import { RANGES, type KpiChart, type KpiTile, type RangePoint, type ReportRange } from '@/types/insights';
import { type TrendChart } from './reports-data';

// ---------------------------------------------------------------------------
// SVG geometry helpers
// ---------------------------------------------------------------------------

const CHART_W = 720;
const CHART_H = 230;
const CHART_PAD = 14;

/**
 * Convert a RangePoint series to the `74×30` sparkline polyline points string
 * used by KpiTrendTile. Values are normalized min→max across the series.
 */
function toSpark(series: RangePoint[]): string {
  if (series.length === 0) return '';
  if (series.length === 1) return `0,15`;
  const vals = series.map((p) => p.value);
  const mx = Math.max(...vals);
  const mn = Math.min(...vals);
  const rng = mx - mn || 1;
  const step = 74 / (vals.length - 1);
  return vals
    .map((v, i) => `${(i * step).toFixed(1)},${(4 + (1 - (v - mn) / rng) * 22).toFixed(1)}`)
    .join(' ');
}

/** Map a RangePoint series to an SVG polyline string in the 720×230 chart viewBox. */
function polyline(series: RangePoint[], maxVal: number): string {
  const n = series.length;
  if (n === 0) return '';
  if (n === 1) return `0,${CHART_H / 2}`;
  return series
    .map((p, i) => {
      const x = (i / (n - 1)) * CHART_W;
      const y = CHART_PAD + (1 - p.value / maxVal) * (CHART_H - CHART_PAD * 2);
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');
}

/** x-axis tick labels for the chart bottom rail. */
function axisFor(range: ReportRange): string[] {
  if (range === '7D') return ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
  if (range === 'YTD') return ['Jan', 'Mar', 'May', 'Jul', 'Sep', 'Nov'];
  if (range === '90D') return ['12w', '10w', '8w', '6w', '4w', 'now'];
  return ['30d', '24d', '18d', '12d', '6d', 'now'];
}

/** Convert a live KpiChart (raw numbers) to SVG polyline strings for TrendChartCard. */
function toTrendChart(chart: KpiChart, range: ReportRange): TrendChart {
  const allVals = [...chart.outbound.map((p) => p.value), ...chart.received.map((p) => p.value)];
  const maxVal = (Math.max(0, ...allVals) * 1.12) || 1;
  const lineShip = polyline(chart.outbound, maxVal);
  const lineRecv = polyline(chart.received, maxVal);
  const areaShip = lineShip
    ? `0,${CHART_H - CHART_PAD} ${lineShip} ${CHART_W},${CHART_H - CHART_PAD}`
    : '';
  return { lineShip, lineRecv, areaShip, axis: axisFor(range) };
}

// ---------------------------------------------------------------------------
// Page component
// ---------------------------------------------------------------------------

/**
 * Reports & Trends (Screen 7) — historical analytics + saved/scheduled reports.
 * Renders inside the existing app shell (sidebar + topbar provided by AppShell).
 *
 * KPI tiles and the trend chart are driven by the live `GET /api/v1/insights/kpis`
 * endpoint. Category breakdown (`GET /api/v1/insights/volume-by-category`, B18) and
 * saved reports (`/api/v1/report-definitions`, B19) are also real, honest-empty when
 * there is no data yet.
 */
export function ReportsPage() {
  const [range, setRange] = useState<ReportRange>('30D');
  const { data, isLoading, isError } = useKpis(range);

  const trendChart = useMemo<TrendChart>(
    () => (data ? toTrendChart(data.chart, range) : { lineShip: '', lineRecv: '', areaShip: '', axis: axisFor(range) }),
    [data, range],
  );

  return (
    <div data-testid="reports-page">
      {/* HEADER + RANGE */}
      <div className="mb-[18px] flex items-end justify-between">
        <div>
          <h1 className="font-display m-0 text-[24px] font-bold tracking-[-0.02em] text-foreground">
            Reports &amp; trends
          </h1>
          <p className="m-0 mt-[5px] text-[13px] text-muted-foreground">
            Riverside DC · {data?.rangeLabel ?? '…'} vs prior period
          </p>
        </div>
        <div className="numeric flex gap-[3px] rounded-[11px] border border-border bg-card p-1">
          {RANGES.map((r) => {
            const active = r === range;
            return (
              <button
                key={r}
                type="button"
                onClick={() => setRange(r)}
                className={cn(
                  'rounded-[8px] px-[14px] py-1.5 text-[12px] font-semibold transition-colors',
                  active
                    ? 'bg-primary text-primary-foreground'
                    : 'bg-transparent text-muted-foreground hover:text-foreground',
                )}
              >
                {r}
              </button>
            );
          })}
        </div>
      </div>

      {/* PAGE-LEVEL ACTIONS */}
      <div className="mb-[18px] flex justify-end gap-[10px]">
        <button
          type="button"
          onClick={() =>
            toast('Export CSV', { description: 'CSV export is not wired yet.' })
          }
          className="flex h-[38px] items-center gap-[7px] rounded-[10px] border border-border bg-secondary px-[14px] text-[13px] font-medium text-foreground/85 transition-colors hover:bg-accent"
        >
          <Download className="h-[15px] w-[15px]" />
          Export CSV
        </button>
        <button
          type="button"
          onClick={() =>
            toast('Build report', { description: 'Report builder lands in a later milestone.' })
          }
          className="flex h-[38px] items-center gap-[7px] rounded-[10px] bg-primary px-[15px] text-[13px] font-bold text-primary-foreground transition-opacity hover:opacity-90"
        >
          <Plus className="h-4 w-4" strokeWidth={2.4} />
          Build report
        </button>
      </div>

      {/* KPI TREND TILES */}
      {isError && (
        <p className="mb-[18px] rounded-[10px] border border-destructive/30 bg-destructive/10 px-4 py-3 text-[13px] text-destructive">
          Could not load KPI data — please try refreshing.
        </p>
      )}
      {!isLoading && data && data.tiles.length === 0 && (
        <p className="mb-[18px] rounded-[10px] border border-border bg-card px-4 py-3 text-[13px] text-muted-foreground">
          No KPI data yet.
        </p>
      )}
      <div className="mb-[18px] flex flex-col gap-4 sm:flex-row">
        {isLoading
          ? [0, 1, 2, 3].map((i) => (
              <div
                key={i}
                className="min-w-0 flex-1 animate-pulse rounded-[14px] bg-card"
                style={{ height: 100 }}
              />
            ))
          : (data?.tiles ?? []).map((t) => <KpiTrendTile key={t.key} tile={t} />)}
      </div>

      {/* MAIN GRID */}
      <div className="mb-[18px] flex flex-col items-start gap-[18px] lg:flex-row">
        <TrendChartCard chart={trendChart} rangeLabel={data?.rangeLabel ?? '…'} />
        <CategoryBreakdownCard range={range} />
      </div>

      {/* SAVED REPORTS */}
      <SavedReportsCard />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

/** KPI trend tile: label, big mono value, inline sparkline, delta pill. */
function KpiTrendTile({ tile }: { tile: KpiTile }) {
  const spark = toSpark(tile.series);
  const stroke = tile.tone === 'up' ? 'var(--acc-color)' : 'var(--warning-foreground)';
  const deltaClass = tile.tone === 'up' ? 'text-primary' : 'text-warning-foreground';
  return (
    <div className="min-w-0 flex-1 rounded-[14px] border border-border bg-card p-4">
      <div className="text-[10px] font-semibold uppercase tracking-[0.12em] text-muted-foreground/70">
        {tile.label}
      </div>
      <div className="mt-3 flex items-end justify-between">
        <span className="numeric text-[26px] font-bold leading-none tracking-[-0.02em] text-foreground">
          {tile.value}
        </span>
        <svg width="74" height="30" viewBox="0 0 74 30" fill="none" preserveAspectRatio="none" aria-hidden>
          <polyline
            points={spark}
            stroke={stroke}
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </div>
      {tile.delta != null && (
        <div className="mt-[11px] flex items-center gap-[7px]">
          <span className={cn('numeric text-[11px] font-bold', deltaClass)}>{tile.delta}</span>
          <span className="text-[11px] text-muted-foreground/70">vs prior</span>
        </div>
      )}
    </div>
  );
}

/** Trend chart "Picked vs received" — inline SVG, area + two polylines. */
function TrendChartCard({
  chart,
  rangeLabel,
}: {
  chart: TrendChart;
  rangeLabel: string;
}) {
  return (
    <section className="min-w-0 flex-1 rounded-2xl border border-border bg-card p-[22px] lg:flex-[1.7]">
      <div className="mb-[18px] flex items-start justify-between">
        <div>
          <h2 className="m-0 text-[15px] font-semibold text-foreground">Picked vs received</h2>
          <p className="numeric m-0 mt-1 text-[11px] tracking-[0.04em] text-muted-foreground/80">
            {rangeLabel} · DAILY
          </p>
        </div>
        <div className="flex gap-4">
          <LegendItem color="var(--acc-color)" label="Picked" />
          <LegendItem color="var(--info)" label="Received" />
        </div>
      </div>

      <div className="relative h-[230px]">
        <svg
          width="100%"
          height="100%"
          viewBox="0 0 720 230"
          preserveAspectRatio="none"
          className="overflow-visible"
          aria-hidden
        >
          {[14, 72, 130, 188].map((y) => (
            <line key={y} x1="0" y1={y} x2="720" y2={y} stroke="var(--border)" strokeWidth="1" />
          ))}
          <polygon points={chart.areaShip} fill="var(--acc-soft)" />
          <polyline
            points={chart.lineRecv}
            fill="none"
            stroke="var(--info)"
            strokeWidth="2.5"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
          <polyline
            points={chart.lineShip}
            fill="none"
            stroke="var(--acc-color)"
            strokeWidth="2.5"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </div>
      <div className="mt-2.5 flex justify-between">
        {chart.axis.map((a) => (
          <span key={a} className="numeric text-[10px] text-muted-foreground/70">
            {a}
          </span>
        ))}
      </div>
    </section>
  );
}

function LegendItem({ color, label }: { color: string; label: string }) {
  return (
    <div className="flex items-center gap-[7px]">
      <span className="h-[3px] w-2.5 rounded-sm" style={{ background: color }} />
      <span className="text-[12px] text-foreground/70">{label}</span>
    </div>
  );
}

const CATEGORY_BAR_COLORS = [
  'var(--acc-color)',
  'var(--info)',
  'var(--warning-foreground)',
  'var(--chart-4)',
  'var(--muted-foreground)',
  'color-mix(in oklab, var(--muted-foreground) 65%, transparent)',
];

/** Volume by category — real aggregation from GET /api/v1/insights/volume-by-category (B18). */
function CategoryBreakdownCard({ range }: { range: ReportRange }) {
  const { data, isLoading, isError } = useVolumeByCategory(range);
  const total = useMemo(() => (data ?? []).reduce((sum, c) => sum + c.volume, 0), [data]);

  return (
    <section className="min-w-0 flex-1 rounded-2xl border border-border bg-card p-[22px]">
      <h2 className="m-0 mb-1 text-[15px] font-semibold text-foreground">Volume by category</h2>
      <p className="numeric m-0 mb-[18px] text-[11px] tracking-[0.04em] text-muted-foreground/80">
        SHARE OF UNITS PICKED
      </p>

      {isLoading && (
        <div className="flex flex-col gap-3">
          {[0, 1, 2].map((i) => (
            <div key={i} className="h-6 animate-pulse rounded-md bg-secondary" />
          ))}
        </div>
      )}

      {!isLoading && isError && (
        <p className="py-6 text-center text-[13px] text-destructive">
          Could not load category data — please try refreshing.
        </p>
      )}

      {!isLoading && !isError && (data == null || data.length === 0) && (
        <div className="py-10 text-center">
          <p className="text-[13px] text-muted-foreground/70">No category data yet.</p>
          <p className="mt-1 text-[11.5px] text-muted-foreground/60">
            Volume by category appears once picks are recorded.
          </p>
        </div>
      )}

      {!isLoading && !isError && data != null && data.length > 0 && (
        <div className="flex flex-col gap-[14px]">
          {data.map((c, i) => {
            const pct = total > 0 ? (c.volume / total) * 100 : 0;
            return (
              <div key={c.category}>
                <div className="mb-[6px] flex items-center justify-between">
                  <span className="text-[12.5px] text-foreground/85">{c.category}</span>
                  <span className="numeric text-[11.5px] text-muted-foreground">
                    {c.volume.toLocaleString()} · {pct.toFixed(1)}%
                  </span>
                </div>
                <div className="h-[7px] w-full overflow-hidden rounded-full bg-secondary">
                  <div
                    className="h-full rounded-full"
                    style={{
                      width: `${Math.max(pct, 1)}%`,
                      background: CATEGORY_BAR_COLORS[i % CATEGORY_BAR_COLORS.length],
                    }}
                  />
                </div>
              </div>
            );
          })}
        </div>
      )}
    </section>
  );
}

const inputCls =
  'h-10 w-full rounded-xl border border-border bg-background px-3 text-[13px] text-foreground';

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="mb-3">
      <div className="numeric mb-1.5 text-[10px] font-semibold uppercase tracking-[0.12em] text-muted-foreground/70">
        {label}
      </div>
      {children}
    </div>
  );
}

/**
 * New-saved-report dialog content: name + report type, backed by POST /api/v1/report-definitions.
 * Rendered as a child of the `<Dialog>` in [SavedReportsCard] — not its own Dialog root.
 */
function NewReportDialogContent({ onClose }: { onClose: () => void }) {
  const create = useCreateReportDefinition();
  const [name, setName] = useState('');
  const [reportType, setReportType] = useState('');

  async function submit() {
    if (!name.trim() || !reportType.trim()) {
      toast.error('Name and report type are required');
      return;
    }
    try {
      await create.mutateAsync({ name: name.trim(), reportType: reportType.trim() });
      toast('Report saved', { description: name.trim() });
      setName('');
      setReportType('');
      onClose();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Failed to save report');
    }
  }

  return (
    <DialogContent className="sm:max-w-md">
      <DialogHeader>
        <DialogTitle>New saved report</DialogTitle>
      </DialogHeader>
      <Field label="Name">
        <input
          className={inputCls}
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="Weekly throughput"
        />
      </Field>
      <Field label="Report type">
        <input
          className={inputCls}
          value={reportType}
          onChange={(e) => setReportType(e.target.value)}
          placeholder="throughput"
        />
      </Field>
      <button
        type="button"
        onClick={() => void submit()}
        disabled={create.isPending}
        className="font-display mt-1 h-11 w-full rounded-xl bg-primary text-[14px] font-bold text-primary-foreground disabled:cursor-not-allowed disabled:opacity-60"
      >
        {create.isPending ? 'Saving…' : 'Save report'}
      </button>
    </DialogContent>
  );
}

/** Saved-reports table — real store, GET/POST/DELETE /api/v1/report-definitions (B19). */
function SavedReportsCard() {
  const { data, isLoading, isError } = useReportDefinitions();
  const del = useDeleteReportDefinition();
  const { hasPermission } = usePermissions();
  const canWrite = hasPermission('report-write');
  const [dialogOpen, setDialogOpen] = useState(false);

  async function handleDelete(id: number, name: string) {
    try {
      await del.mutateAsync(id);
      toast('Report deleted', { description: name });
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Failed to delete report');
    }
  }

  return (
    <section className="overflow-hidden rounded-2xl border border-border bg-card">
      <div className="flex items-center justify-between border-b border-border p-[16px_20px]">
        <div>
          <h2 className="m-0 text-[15px] font-semibold text-foreground">Saved reports</h2>
          <p className="numeric m-0 mt-[3px] text-[11px] tracking-[0.04em] text-muted-foreground/80">
            SAVED &amp; ON-DEMAND
          </p>
        </div>
        <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
          <DialogTrigger asChild>
            <button
              type="button"
              disabled={!canWrite}
              className="numeric text-[11px] tracking-[0.06em] text-primary transition-opacity hover:opacity-80 disabled:cursor-not-allowed disabled:opacity-40"
            >
              NEW REPORT →
            </button>
          </DialogTrigger>
          <NewReportDialogContent onClose={() => setDialogOpen(false)} />
        </Dialog>
      </div>

      {isLoading && (
        <div className="flex flex-col gap-2 p-[16px_20px]">
          {[0, 1].map((i) => (
            <div key={i} className="h-9 animate-pulse rounded-md bg-secondary" />
          ))}
        </div>
      )}

      {!isLoading && isError && (
        <p className="py-8 text-center text-[13px] text-destructive">
          Could not load saved reports — please try refreshing.
        </p>
      )}

      {!isLoading && !isError && (data == null || data.length === 0) && (
        <div className="py-12 text-center">
          <p className="text-[13px] text-muted-foreground/70">No saved reports yet.</p>
          <p className="mt-1 text-[11.5px] text-muted-foreground/60">
            Save a report to reuse it later — it&apos;ll show up here.
          </p>
        </div>
      )}

      {!isLoading && !isError && data != null && data.length > 0 && (
        <table className="w-full text-left">
          <thead>
            <tr className="text-[10px] uppercase tracking-[0.1em] text-muted-foreground/70">
              <th className="p-[10px_20px] font-semibold">Name</th>
              <th className="p-[10px_20px] font-semibold">Type</th>
              <th className="p-[10px_20px] font-semibold">Owner</th>
              <th className="p-[10px_20px] font-semibold">Created</th>
              {canWrite && <th className="p-[10px_20px] font-semibold" />}
            </tr>
          </thead>
          <tbody>
            {data.map((r) => (
              <tr key={r.id} className="border-t border-border">
                <td className="p-[10px_20px] text-[13px] text-foreground/85">{r.name}</td>
                <td className="p-[10px_20px] text-[12.5px] text-muted-foreground">{r.reportType}</td>
                <td className="p-[10px_20px] text-[12.5px] text-muted-foreground">{r.owner ?? '—'}</td>
                <td className="numeric p-[10px_20px] text-[11.5px] text-muted-foreground">
                  {new Date(r.created).toLocaleDateString()}
                </td>
                {canWrite && (
                  <td className="p-[10px_20px] text-right">
                    <button
                      type="button"
                      onClick={() => void handleDelete(r.id, r.name)}
                      aria-label={`Delete ${r.name}`}
                      className="text-muted-foreground transition-colors hover:text-destructive"
                    >
                      <Trash2 className="size-[15px]" />
                    </button>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
