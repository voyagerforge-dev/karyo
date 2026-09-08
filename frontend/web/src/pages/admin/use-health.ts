import { useQuery } from '@tanstack/react-query';

/** One SmallRye Health check row (Quarkus `/q/health` response shape). */
export interface HealthCheck {
  name: string;
  status: 'UP' | 'DOWN';
  data?: Record<string, unknown>;
}

/** Overall SmallRye Health report. */
export interface HealthReport {
  status: 'UP' | 'DOWN';
  checks: HealthCheck[];
}

/**
 * Fetches Quarkus SmallRye Health directly — NOT through the `api` client:
 * `/q/health` is unauthenticated and doesn't return an RFC 7807 body, so
 * routing it through `apiFetch` would attach a JWT it doesn't need and
 * mis-parse its errors. Plain `fetch()` on a same-origin path instead (nginx
 * proxies `/q/` alongside `/api/` in front of karyo-app).
 *
 * Tries `/q/health` (the full liveness+readiness aggregate) first, then
 * falls back to `/q/health/ready` if that's unreachable. If both fail, the
 * caller gets `isError` — the page shows an honest error state, never a
 * fabricated "UP".
 */
async function fetchHealthReport(url: string): Promise<HealthReport> {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`${url} returned ${response.status}`);
  }
  return response.json();
}

async function fetchHealth(): Promise<HealthReport> {
  try {
    return await fetchHealthReport('/q/health');
  } catch {
    return await fetchHealthReport('/q/health/ready');
  }
}

export function useHealth() {
  return useQuery({
    queryKey: ['admin-health'],
    queryFn: fetchHealth,
    staleTime: 15_000,
    retry: false,
  });
}
