import { api } from '@/lib/api-client';

/** Tenant entitlements for paid modules (e.g. `monitors`, `forecasting`). */
export interface LicenseInfo {
  /** `community` on a free build, `commercial` on one carrying paid engines. */
  edition: string;
  /** Authenticated callers only; absent from the anonymous edition-only body. */
  entitlements?: string[];
}

/**
 * Gate-discovery endpoint — NOT gated by any entitlement itself, so the UI can
 * always ask "what do I have?" to decide whether to render a feature or its
 * locked/upsell state. `entitlements` is served to authenticated callers only;
 * an anonymous caller gets `edition` alone. `main.tsx` forces login before
 * render, so the dashboard is always the authenticated caller.
 */
export const getLicense = () => api.get<LicenseInfo>('/api/v1/license');
