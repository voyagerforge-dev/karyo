import {
  Blocks,
  Sparkles,
  Plug,
  Briefcase,
  Building2,
  Users,
  ScrollText,
  Activity,
  Flag,
  FileText,
  LayoutTemplate,
  SlidersHorizontal,
  Package,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { SPI_CATALOG } from '@/pages/admin/spi-catalog';

/**
 * Admin shell navigation (v3 — separate IT/admin surface, violet chrome).
 * Extensions (SPI), Integrations, Health, Audit log and Clients are wired to
 * real backends; Users & roles honestly deep-links out to the Keycloak admin
 * console (no local user store exists) instead of faking a page. Copilot /
 * Tenants / Feature flags stay placeholders ("coming soon") — there is no
 * backend for them yet. Clients (goods-owner administration, `/api/v1/clients`)
 * is distinct from the still-placeholder Tenants entry: Karyo is silo-tenancy
 * (one instance per company), so "Tenants" here would be a future SaaS
 * concept, not the same thing as a Client (myWMS goods-owner / 3PL customer).
 */
export type AdminNavGroup = 'Configure' | 'Govern' | 'System';

export interface AdminNavItem {
  title: string;
  url?: string; // undefined = placeholder (not yet built)
  icon: LucideIcon;
  group: AdminNavGroup;
  badge?: number;
  /** True when `url` is an external destination (opens in a new tab, not an in-app route). */
  external?: boolean;
  /**
   * Realm permission required to see (and navigate to) this item. Defaults
   * to `'user-admin'` when omitted. `Integrations` is the one exception,
   * gated on the narrower `integration-admin` permission so `manager`
   * (which holds `integration-admin` but not `user-admin`) sees the admin
   * section with only that entry — mirrors the route-level split in
   * `router.tsx` (two nested `AdminGuard`s under one ungated `AdminShell`).
   */
  permission?: string;
}

export const ADMIN_NAV_GROUP_ORDER: AdminNavGroup[] = ['Configure', 'Govern', 'System'];

/**
 * Resolves the Keycloak base URL the same way `lib/keycloak.ts` does
 * (runtime `window.__ENV__.KEYCLOAK_URL` injected by nginx envsubst ->
 * build-time `VITE_KEYCLOAK_URL` -> `/auth` dev default). Duplicated here
 * (rather than importing the `keycloak` singleton) so this stays a plain,
 * side-effect-free config module — importing `lib/keycloak.ts` would
 * construct a real `Keycloak` instance as a module-load side effect.
 */
function keycloakBaseUrl(): string {
  const injected = (window as unknown as { __ENV__?: { KEYCLOAK_URL?: string } }).__ENV__
    ?.KEYCLOAK_URL;
  if (injected && !injected.includes('${')) return injected;
  return import.meta.env.VITE_KEYCLOAK_URL || '/auth';
}

/** Deep link to the Keycloak admin console (master realm — the standard admin login). */
export const keycloakAdminConsoleUrl = `${keycloakBaseUrl()}/admin/master/console/`;

export const adminNavItems: AdminNavItem[] = [
  { title: 'Extensions (SPI)', url: '/admin/strategies', icon: Blocks, group: 'Configure', badge: SPI_CATALOG.length, permission: 'user-admin' },
  { title: 'Copilot', icon: Sparkles, group: 'Configure', permission: 'user-admin' },
  { title: 'Integrations', url: '/admin/integrations', icon: Plug, group: 'Configure', permission: 'integration-admin' },
  { title: 'System properties', url: '/admin/properties', icon: SlidersHorizontal, group: 'Configure', permission: 'user-admin' },
  { title: 'Unit load types', url: '/admin/unit-load-types', icon: Package, group: 'Configure', permission: 'user-admin' },
  { title: 'Clients', url: '/admin/clients', icon: Briefcase, group: 'Govern', permission: 'user-admin' },
  { title: 'Tenants', icon: Building2, group: 'Govern', permission: 'user-admin' },
  { title: 'Users & roles', url: keycloakAdminConsoleUrl, external: true, icon: Users, group: 'Govern', permission: 'user-admin' },
  { title: 'Audit log', url: '/admin/audit', icon: ScrollText, group: 'Govern', permission: 'user-admin' },
  { title: 'Documents', url: '/admin/documents', icon: FileText, group: 'Govern', permission: 'user-admin' },
  { title: 'Document templates', url: '/admin/document-templates', icon: LayoutTemplate, group: 'Govern', permission: 'user-admin' },
  { title: 'Health', url: '/admin/health', icon: Activity, group: 'System', permission: 'user-admin' },
  { title: 'Feature flags', icon: Flag, group: 'System', permission: 'user-admin' },
];

/**
 * Filters `adminNavItems` down to what a principal with the given
 * `hasPermission` check may see. Each item's `permission` (default
 * `'user-admin'` when unset) must be held. Pure and framework-free so it can
 * be unit-tested without rendering `AdminShell`.
 */
export function visibleAdminNavItems(hasPermission: (permission: string) => boolean): AdminNavItem[] {
  return adminNavItems.filter((item) => hasPermission(item.permission ?? 'user-admin'));
}
