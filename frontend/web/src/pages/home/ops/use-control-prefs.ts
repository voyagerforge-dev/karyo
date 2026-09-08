import { useEffect, useState } from 'react';

/**
 * Console Workspace prefs (v3) — the productized design-time "tweaks", now
 * user-settable at runtime and persisted to localStorage. Accent also drives
 * `data-accent` on <html> (see the [data-accent] blocks in index.css), which
 * recolors the whole --acc signal system. Density lives in its own hook
 * (use-density) and is surfaced by the Workspace panel separately.
 *
 * `copilotMode`/`zoneMetric` were dropped in demo-hardening Task 3 — both
 * drove mock-only tiles (CopilotStrip, the 3-metric ZoneHeatmap switcher)
 * that were deleted when the dashboard was rewired to real data.
 */
export type Accent = 'lime' | 'cyan' | 'amber' | 'violet';

export interface ControlPrefs {
  accent: Accent;
}

const KEY = 'karyo.control.ws';
const DEFAULTS: ControlPrefs = {
  accent: 'lime',
};

function read(): ControlPrefs {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? { ...DEFAULTS, ...JSON.parse(raw) } : DEFAULTS;
  } catch {
    return DEFAULTS;
  }
}

export interface UseControlPrefs extends ControlPrefs {
  set: <K extends keyof ControlPrefs>(key: K, value: ControlPrefs[K]) => void;
}

export function useControlPrefs(): UseControlPrefs {
  const [prefs, setPrefs] = useState<ControlPrefs>(read);

  // Accent → data-accent on <html> (recolors --acc system).
  useEffect(() => {
    document.documentElement.setAttribute('data-accent', prefs.accent);
  }, [prefs.accent]);

  const set: UseControlPrefs['set'] = (key, value) => {
    setPrefs((prev) => {
      const next = { ...prev, [key]: value };
      try {
        localStorage.setItem(KEY, JSON.stringify(next));
      } catch {
        /* ignore quota/private-mode errors */
      }
      return next;
    });
  };

  return { ...prefs, set };
}
