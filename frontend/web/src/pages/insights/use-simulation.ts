import { useQuery } from '@tanstack/react-query';
import { getSimulation } from './simulation-api';

/**
 * Reorder what-if backtest for the Insights → Simulation screen. Only ever invoked once
 * the caller has confirmed `simulation` license entitlement (`simulation-page.tsx` mounts
 * this hook only after the `useLicense()` gate passes) — unentitled tenants never hit the
 * endpoint.
 */
export function useSimulation() {
  return useQuery({ queryKey: ['simulation'], queryFn: getSimulation });
}
