import { useQuery } from '@tanstack/react-query';
import { useLicense } from '@/features/license/use-license';
import { getForecasts, type SkuForecast } from '@/pages/insights/forecasting-api';
import { getSlotting, type ReSlotSuggestion } from '@/pages/insights/slotting-api';

/**
 * SKU-keyed forecasting + slotting lookups for the Items screen. Both paid
 * engines are license-gated (`forecasting` / `slotting`) — the underlying
 * queries only fire once entitlement is confirmed (`enabled`), so an
 * unlicensed instance never calls either endpoint and `/items` degrades to
 * identity + stock only. Reuses the same query keys as
 * `pages/insights/use-forecasts.ts` / `use-slotting.ts` so the cache is
 * shared with the Insights pages when both are visited in a session.
 *
 * Loading is surfaced (`forecastLoading` / `slottingLoading`) so callers can
 * tell "still resolving" from "resolved, absent" and avoid asserting absence
 * ("— no recent demand", "not licensed") before the license/query has
 * settled — mirrors `use-ops-data.ts`, where entitlement isn't treated as
 * decided while `license.isLoading`.
 */
export function useItemAnalytics(): {
  forecastBySku: Map<string, SkuForecast>;
  slottingBySku: Map<string, ReSlotSuggestion>;
  forecastEntitled: boolean;
  slottingEntitled: boolean;
  forecastLoading: boolean;
  slottingLoading: boolean;
} {
  const license = useLicense();
  const forecastEntitled = license.isEntitled('forecasting');
  const slottingEntitled = license.isEntitled('slotting');

  const forecastQuery = useQuery({
    queryKey: ['forecasts'],
    queryFn: getForecasts,
    enabled: forecastEntitled,
  });
  const slottingQuery = useQuery({
    queryKey: ['slotting'],
    queryFn: getSlotting,
    enabled: slottingEntitled,
  });

  const forecastBySku = new Map((forecastQuery.data ?? []).map((f) => [f.sku, f]));
  const slottingBySku = new Map((slottingQuery.data ?? []).map((s) => [s.sku, s]));

  return {
    forecastBySku,
    slottingBySku,
    forecastEntitled,
    slottingEntitled,
    // Still resolving while the license is loading (entitlement undecided), or
    // while an entitled tenant's gated query is in flight.
    forecastLoading: license.isLoading || (forecastEntitled && forecastQuery.isLoading),
    slottingLoading: license.isLoading || (slottingEntitled && slottingQuery.isLoading),
  };
}
