/**
 * Pick `state` is returned by the backend as a raw Int (no `stateName`), so the
 * frontend maps codes -> labels/badge-variants here. Variants mirror the
 * app-wide "Precision" semantic chips in state-variants.ts (success/warning/error).
 */
import type { VariantProps } from 'class-variance-authority';
import type { badgeVariants } from '@/components/ui/badge';

type BadgeVariant = NonNullable<VariantProps<typeof badgeVariants>['variant']>;

export const PICK_STATES = [
  { code: 50, name: 'CREATED' },
  { code: 100, name: 'RELEASED' },
  { code: 500, name: 'STARTED' },
  { code: 600, name: 'PICKED' },
  { code: 800, name: 'CANCELED' },
] as const;

export function pickStateName(code: number): string {
  return PICK_STATES.find((s) => s.code === code)?.name ?? String(code);
}

export function pickStateVariant(code: number): BadgeVariant {
  switch (code) {
    case 600:
      return 'success'; // PICKED — done
    case 800:
      return 'error'; // CANCELED — terminal
    case 500:
      return 'warning'; // STARTED — in progress
    default:
      return 'secondary'; // CREATED / RELEASED
  }
}

/** "3/5 picked" — counts picks in PICKED(600). */
export function pickProgress(picks: { state: number }[]): { done: number; total: number } {
  return { done: picks.filter((p) => p.state === 600).length, total: picks.length };
}
