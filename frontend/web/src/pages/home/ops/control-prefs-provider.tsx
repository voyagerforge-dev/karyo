import { useEffect, useState, type ReactNode } from 'react';
import {
  ControlPrefsContext,
  CONTROL_PREFS_DEFAULTS,
  DENSITY_KEY,
  WS_KEY,
  type ControlPrefsContextValue,
  type ControlPrefsState,
} from '@/pages/home/ops/control-prefs-context';

/**
 * Provider for the shared Console Workspace prefs (v3). Owns accent / density
 * for the whole operator surface (mounted in AppShell).
 *
 * Persistence + DOM effects (folded in from the standalone use-control-prefs /
 * use-density hooks):
 *  - accent  → `data-accent` on <html> (the .theme-control[data-accent] rules
 *              in index.css recolor the whole --acc signal system).
 *  - density → `data-density` on <html> (the [data-density] rules swap the
 *              spacing vars --cpad/--kpad/--cgap/--secmb/--copad).
 * Both persist to localStorage and seed from it on mount. `copilotMode`/
 * `zoneMetric` were dropped in demo-hardening Task 3 (see use-control-prefs.ts).
 */

/**
 * Seed from the two existing localStorage keys (kept separate for backward
 * compatibility): `karyo.control.ws` holds accent; `karyo-density` holds
 * density (as the use-density hook wrote it).
 */
function read(): ControlPrefsState {
  let next = { ...CONTROL_PREFS_DEFAULTS };
  try {
    const raw = localStorage.getItem(WS_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<ControlPrefsState>;
      next = {
        ...next,
        accent: parsed.accent ?? next.accent,
      };
    }
  } catch {
    /* ignore */
  }
  try {
    if (localStorage.getItem(DENSITY_KEY) === 'command') {
      next.density = 'command';
    }
  } catch {
    /* ignore */
  }
  return next;
}

export function ControlPrefsProvider({ children }: { children: ReactNode }) {
  const [prefs, setPrefs] = useState<ControlPrefsState>(read);

  // accent → data-accent on <html>
  useEffect(() => {
    document.documentElement.setAttribute('data-accent', prefs.accent);
  }, [prefs.accent]);

  // density → data-density on <html>
  useEffect(() => {
    document.documentElement.setAttribute('data-density', prefs.density);
  }, [prefs.density]);

  const set: ControlPrefsContextValue['set'] = (key, value) => {
    setPrefs((prev) => {
      const next = { ...prev, [key]: value };
      try {
        // density keeps its own key (use-density compat); the rest stay in WS_KEY.
        if (key === 'density') {
          localStorage.setItem(DENSITY_KEY, next.density);
        } else {
          const { accent } = next;
          localStorage.setItem(WS_KEY, JSON.stringify({ accent }));
        }
      } catch {
        /* ignore quota / private-mode errors */
      }
      return next;
    });
  };

  return (
    <ControlPrefsContext.Provider value={{ ...prefs, set }}>{children}</ControlPrefsContext.Provider>
  );
}
