import { useQuery } from '@tanstack/react-query';
import { getForecasts } from './forecasting-api';

/**
 * Per-SKU reorder suggestions for the Insights → Forecasting screen.
 * Only ever invoked once the caller has confirmed `forecasting` license
 * entitlement (`forecasting-page.tsx` mounts this hook only after the
 * `useLicense()` gate passes) — unentitled tenants never hit the endpoint.
 */
export function useForecasts() {
  return useQuery({ queryKey: ['forecasts'], queryFn: getForecasts });
}
