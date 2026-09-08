import { useQuery } from '@tanstack/react-query';
import { useKpis } from '@/features/insights/use-kpis';
import { useOccupancy } from '@/features/insights/use-occupancy';
import { useLicense } from '@/features/license/use-license';
import { getAlerts } from '@/pages/monitors/monitors-api';
import type { ReportRange } from '@/types/insights';
import {
  kpisToCells,
  kpiChartToThroughput,
  occupancyToZoneField,
  alertsToExceptions,
  type KpiCell,
  type ThroughputView,
  type ZoneField,
  type ExceptionRow,
} from './ops-adapters';

export interface OpsData {
  kpis: KpiCell[];
  throughput: ThroughputView | null;
  zone: ZoneField | null;
  exceptions: ExceptionRow[];
  /** True once the monitors license entitlement has loaded and is present. */
  monitorsEntitled: boolean;
  /**
   * Still resolving while the license is loading (entitlement undecided), or
   * while an entitled tenant's gated alerts query is in flight — mirrors
   * `useItemAnalytics`' `forecastLoading`/`slottingLoading` pattern so
   * `ExceptionsCard` can show a neutral loading placeholder instead of
   * asserting a false absence ("not licensed" / "no open exceptions").
   */
  monitorsLoading: boolean;
  isLoading: boolean;
}

/**
 * Assembles real API data for the home "Operations Control" dashboard's 4
 * kept tiles (KPI strip, throughput, zone heatmap, exceptions) via the pure
 * adapters in `ops-adapters.ts`.
 *
 * `useMonitors()` (the Event Monitors screen's hook) doesn't expose the raw
 * `AlertDto[]` list it fetches internally, so firing alerts are queried here
 * directly against `GET /api/v1/alerts?status=FIRING` — same query key as
 * `use-monitors.ts` (`['alerts', 'FIRING']`) so React Query dedupes/caches
 * consistently if both screens are mounted. The query is only enabled once
 * the `monitors` entitlement is confirmed, so an unlicensed instance never
 * hits (and 403s against) the gated endpoint.
 */
export function useOpsData(range: ReportRange): OpsData {
  const license = useLicense();
  const monitorsEntitled = license.isEntitled('monitors');

  const kpisQuery = useKpis(range);
  const occupancyQuery = useOccupancy();
  const alertsQuery = useQuery({
    queryKey: ['alerts', 'FIRING'],
    queryFn: () => getAlerts('FIRING'),
    staleTime: 10_000,
    enabled: monitorsEntitled,
  });

  // Only a real, loaded firing count becomes a number; `null` (→ KPI cell
  // omitted) covers both "not entitled" and "entitled but still fetching",
  // so a genuine zero is never confused with an unresolved fetch.
  const openExceptions =
    monitorsEntitled && alertsQuery.data ? alertsQuery.data.length : null;

  return {
    kpis: kpisQuery.data ? kpisToCells(kpisQuery.data, openExceptions) : [],
    throughput: kpisQuery.data ? kpiChartToThroughput(kpisQuery.data) : null,
    zone: occupancyQuery.data ? occupancyToZoneField(occupancyQuery.data) : null,
    exceptions:
      monitorsEntitled && alertsQuery.data ? alertsToExceptions(alertsQuery.data) : [],
    monitorsEntitled,
    monitorsLoading: license.isLoading || (monitorsEntitled && alertsQuery.isLoading),
    isLoading:
      kpisQuery.isLoading ||
      occupancyQuery.isLoading ||
      (monitorsEntitled && alertsQuery.isLoading),
  };
}
