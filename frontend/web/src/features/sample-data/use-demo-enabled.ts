import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api-client';

/**
 * Gate-discovery probe for `karyo-demo` (`GET /api/v1/demo/status`,
 * `DemoResource.status`, `@RolesAllowed("VIEWER")`). 200 => demo engine on;
 * any failure (404 when KARYO_DEMO is off -- `DemoEnabledFilter` pre-matching
 * 404s the whole route before this even reaches security) => false, and
 * silently: `{ silent: true }` opts out of api-client's global Infinity-
 * duration error toast, since a 404 here is an expected "feature is off"
 * signal, not a user-facing failure.
 *
 * `staleTime: Infinity` -- like `useLicense`, this doesn't change within a
 * session; a full reload picks up any server-side change. `retry: false`
 * keeps the "feature is off" path a single fast request.
 */
export function useDemoEnabled(): boolean {
  const { data } = useQuery({
    queryKey: ['demo-status'],
    queryFn: () => api.get<{ enabled: boolean }>('/api/v1/demo/status', { silent: true }),
    staleTime: Infinity,
    retry: false,
  });
  return data?.enabled ?? false;
}
