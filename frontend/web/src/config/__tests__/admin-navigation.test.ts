import { describe, it, expect } from 'vitest';
import { adminNavItems, visibleAdminNavItems } from '../admin-navigation';
import { SPI_CATALOG } from '@/pages/admin/spi-catalog';

describe('adminNavItems', () => {
  it('has a single "Extensions (SPI)" item at /admin/strategies with a real badge, and no "Strategies" item', () => {
    const ext = adminNavItems.filter((i) => i.title === 'Extensions (SPI)');
    expect(ext).toHaveLength(1);
    expect(ext[0].url).toBe('/admin/strategies');
    expect(ext[0].badge).toBe(SPI_CATALOG.length);
    expect(adminNavItems.some((i) => i.title === 'Strategies')).toBe(false);
  });

  it('wires Health to /admin/health and Audit log to /admin/audit (B17)', () => {
    const health = adminNavItems.find((i) => i.title === 'Health');
    const audit = adminNavItems.find((i) => i.title === 'Audit log');
    expect(health?.url).toBe('/admin/health');
    expect(health?.external).toBeFalsy();
    expect(audit?.url).toBe('/admin/audit');
    expect(audit?.external).toBeFalsy();
  });

  it('wires Clients to /admin/clients (B10-3, distinct from the still-placeholder Tenants)', () => {
    const clients = adminNavItems.find((i) => i.title === 'Clients');
    expect(clients?.url).toBe('/admin/clients');
    expect(clients?.external).toBeFalsy();
  });

  it('deep-links Users & roles to the Keycloak admin console as an external link (no local user store)', () => {
    const users = adminNavItems.find((i) => i.title === 'Users & roles');
    expect(users?.external).toBe(true);
    expect(users?.url).toMatch(/\/admin\/master\/console\/$/);
  });

  it('keeps Copilot, Tenants and Feature flags as honest placeholders (no backend)', () => {
    for (const title of ['Copilot', 'Tenants', 'Feature flags']) {
      const item = adminNavItems.find((i) => i.title === title);
      expect(item?.url).toBeUndefined();
    }
  });

  it('gates Integrations on integration-admin and every other item on user-admin (re-gate rider)', () => {
    const integrations = adminNavItems.find((i) => i.title === 'Integrations');
    expect(integrations?.permission).toBe('integration-admin');

    const others = adminNavItems.filter((i) => i.title !== 'Integrations');
    expect(others.length).toBeGreaterThan(0);
    for (const item of others) {
      expect(item.permission ?? 'user-admin').toBe('user-admin');
    }
  });
});

describe('visibleAdminNavItems', () => {
  it('shows only Integrations to a principal holding integration-admin but not user-admin (e.g. manager)', () => {
    const visible = visibleAdminNavItems((p) => p === 'integration-admin');
    expect(visible.map((i) => i.title)).toEqual(['Integrations']);
  });

  it('shows every item to a principal holding user-admin (and, via the ADMIN composite, integration-admin too)', () => {
    const visible = visibleAdminNavItems((p) => p === 'user-admin' || p === 'integration-admin');
    expect(visible.length).toBe(adminNavItems.length);
  });

  it('shows nothing to a principal with neither permission', () => {
    const visible = visibleAdminNavItems(() => false);
    expect(visible).toHaveLength(0);
  });
});
