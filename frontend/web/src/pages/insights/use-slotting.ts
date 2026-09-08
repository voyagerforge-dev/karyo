import { useQuery } from '@tanstack/react-query';
import { getSlotting } from './slotting-api';

/**
 * Per-SKU re-slot recommendations for the Insights → Slotting screen. Only
 * ever invoked once the caller has confirmed `slotting` license entitlement
 * (`slotting-page.tsx` mounts this hook only after the `useLicense()` gate
 * passes) — unentitled tenants never hit the endpoint.
 */
export function useSlotting() {
  return useQuery({ queryKey: ['slotting'], queryFn: getSlotting });
}
