import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api-client';

/**
 * One row of the live SPI registry (mirrors the backend's `ExtensionInfo`,
 * `GET /api/v1/admin/extensions`, ADMIN-only). The runtime counterpart of
 * the static `SPI_CATALOG` in `spi-catalog.ts` — resolved at request time
 * via `Class.forName` + CDI `BeanManager`, so `implementations` reflects
 * whatever is actually loaded in the running app, not a curated guess.
 */
export interface ExtensionInfo {
  spiInterface: string;
  spiFqn: string;
  module: string;
  implementations: string[];
  implementationCount: number;
}

/**
 * Live extension registry (Phase B14). Backs the Admin -> Extensions (SPI)
 * page's primary view; `admin-strategies-page.tsx` falls back to the static
 * `SPI_CATALOG` when this errors (e.g. non-ADMIN caller, endpoint down) or
 * returns an empty list.
 */
export function useExtensions() {
  return useQuery({
    queryKey: ['admin-extensions'],
    queryFn: () => api.get<ExtensionInfo[]>('/api/v1/admin/extensions'),
    staleTime: 60_000,
    retry: false,
  });
}
