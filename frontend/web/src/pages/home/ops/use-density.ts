import { useState } from 'react';

/**
 * Operations Control density (v2 handoff tweakable prop). Persisted like the
 * theme — seed from localStorage, write-through on change. Drives the
 * `data-density` attribute on the dashboard wrapper, which swaps the spacing
 * CSS vars defined in index.css.
 */
export type Density = 'comfortable' | 'command';

const STORAGE_KEY = 'karyo-density';

function readDensity(): Density {
  return localStorage.getItem(STORAGE_KEY) === 'command' ? 'command' : 'comfortable';
}

export interface UseDensity {
  density: Density;
  setDensity: (next: Density) => void;
}

export function useDensity(): UseDensity {
  const [density, setDensityState] = useState<Density>(readDensity);
  const setDensity = (next: Density) => {
    localStorage.setItem(STORAGE_KEY, next);
    setDensityState(next);
  };
  return { density, setDensity };
}
