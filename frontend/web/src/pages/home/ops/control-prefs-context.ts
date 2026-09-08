import { createContext, useContext } from 'react';
import type { Accent } from '@/pages/home/ops/use-control-prefs';
import type { Density } from '@/pages/home/ops/use-density';

/**
 * Shared Console Workspace prefs (v3) — the context object + types + reader
 * hook. The provider component lives in `control-prefs-provider.tsx` (kept
 * separate so this module is component-free and Fast-Refresh clean).
 *
 * accent / density are owned by one provider mounted in the app shell, so
 * the Workspace panel (in the topbar) and the Operations Control screen body
 * read/write the SAME state. `copilotMode`/`zoneMetric` were dropped in
 * demo-hardening Task 3 (see use-control-prefs.ts).
 */
export interface ControlPrefsState {
  accent: Accent;
  density: Density;
}

export interface ControlPrefsContextValue extends ControlPrefsState {
  set: <K extends keyof ControlPrefsState>(key: K, value: ControlPrefsState[K]) => void;
}

export const WS_KEY = 'karyo.control.ws';
export const DENSITY_KEY = 'karyo-density';

export const CONTROL_PREFS_DEFAULTS: ControlPrefsState = {
  accent: 'lime',
  density: 'comfortable',
};

export const ControlPrefsContext = createContext<ControlPrefsContextValue | null>(null);

/**
 * Read/write the shared Console Workspace prefs. Throws if used outside the
 * provider (it is mounted in AppShell, so the whole operator surface has it).
 */
export function useControlPrefsContext(): ControlPrefsContextValue {
  const ctx = useContext(ControlPrefsContext);
  if (!ctx) {
    throw new Error('useControlPrefsContext must be used within a ControlPrefsProvider');
  }
  return ctx;
}
