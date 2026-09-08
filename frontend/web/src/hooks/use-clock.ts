import { useEffect, useState } from 'react';

/**
 * Ticking HH:MM:SS wall-clock string, cleared on unmount. Shared by the
 * Operations Control dashboard header and the Event Monitors screen — a
 * real (non-mock) UI-only hook, so it lives in the generic `hooks/` folder
 * rather than a feature-specific `ops`/`monitors` module.
 */
export function useClock(): string {
  const [clock, setClock] = useState('');
  useEffect(() => {
    const pad = (n: number) => String(n).padStart(2, '0');
    const tick = () => {
      const d = new Date();
      setClock(`${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`);
    };
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, []);
  return clock;
}
