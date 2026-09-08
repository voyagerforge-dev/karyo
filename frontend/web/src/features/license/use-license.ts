import { useQuery } from '@tanstack/react-query';
import { getLicense } from './license-api';

/**
 * Tenant license entitlements. `staleTime: Infinity` — entitlements don't
 * change within a session; a full reload picks up any change server-side.
 */
export function useLicense() {
  const query = useQuery({ queryKey: ['license'], queryFn: getLicense, staleTime: Infinity });
  return {
    ...query,
    /** True once `data` has loaded and includes `key` among entitlements. */
    isEntitled: (key: string) => !!query.data?.entitlements?.includes(key),
  };
}
