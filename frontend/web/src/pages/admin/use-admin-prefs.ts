import { useState } from 'react';

/**
 * Admin Workspace prefs (v3) — persisted to localStorage. Drive the admin
 * surface: environment (non-prod raises a banner), and whether raw SPI
 * interface ids (com.karyo.*.spi.*) are shown on the Strategies catalog.
 */
export type AdminEnv = 'Production' | 'Staging' | 'Sandbox';

export interface AdminPrefs {
  env: AdminEnv;
  showSpiIds: boolean;
}

const KEY = 'karyo.admin.ws';
const DEFAULTS: AdminPrefs = { env: 'Sandbox', showSpiIds: true };

function read(): AdminPrefs {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? { ...DEFAULTS, ...JSON.parse(raw) } : DEFAULTS;
  } catch {
    return DEFAULTS;
  }
}

export interface UseAdminPrefs extends AdminPrefs {
  set: <K extends keyof AdminPrefs>(key: K, value: AdminPrefs[K]) => void;
}

export function useAdminPrefs(): UseAdminPrefs {
  const [prefs, setPrefs] = useState<AdminPrefs>(read);
  const set: UseAdminPrefs['set'] = (key, value) => {
    setPrefs((prev) => {
      const next = { ...prev, [key]: value };
      try {
        localStorage.setItem(KEY, JSON.stringify(next));
      } catch {
        /* ignore */
      }
      return next;
    });
  };
  return { ...prefs, set };
}
