/**
 * Stock status classification for the v3 Inventory "stock by item × location"
 * screen. Mirrors `Karyo Stock.dc.html`'s `statusStyle`: the record is an
 * Item @ Location group, classified into one of four operational states.
 *
 *   held lock  -> Hold      (red)    — locked / on QA hold
 *   allocated  -> Allocated (amber)  — reservedAmount > 0 (and not held)
 *   available  -> Available (lime)   — availableAmount > 0
 *   else       -> Out       (red)    — nothing left
 *
 * "Damaged" exists in the prototype as a held variant; the backend has no
 * damage flag, so we collapse held stock to a single "Hold" status here.
 */

export type StockStatus = 'Available' | 'Allocated' | 'Hold' | 'Out';
export type StatusTone = 'lime' | 'amber' | 'red';

export interface StatusStyle {
  status: StockStatus;
  tone: StatusTone;
}

export interface StatusInput {
  onHand: number;
  available: number;
  reserved: number;
  /** True when any constituent stock unit carries a lock (lockType !== 0). */
  held: boolean;
}

export function classifyStatus({
  onHand,
  available,
  reserved,
  held,
}: StatusInput): StatusStyle {
  if (held) return { status: 'Hold', tone: 'red' };
  if (onHand <= 0 || available <= 0) {
    // Fully allocated but on hand reads as Allocated; truly empty reads as Out.
    if (onHand > 0 && reserved > 0) return { status: 'Allocated', tone: 'amber' };
    return { status: 'Out', tone: 'red' };
  }
  if (reserved > 0) return { status: 'Allocated', tone: 'amber' };
  return { status: 'Available', tone: 'lime' };
}

/** Tailwind text-color class for a status tone (Control theme tokens). */
export function toneTextClass(tone: StatusTone): string {
  switch (tone) {
    case 'lime':
      return 'text-primary';
    case 'amber':
      return 'text-warning-foreground';
    case 'red':
      return 'text-destructive';
  }
}

/** Tailwind soft-background class for a status pill. */
export function toneBgClass(tone: StatusTone): string {
  switch (tone) {
    case 'lime':
      return 'bg-[var(--acc-soft)]';
    case 'amber':
      return 'bg-warning';
    case 'red':
      return 'bg-error';
  }
}

/** Inline rail / dot color (CSS var string; resolves per-theme) for a status tone. */
export function toneHex(tone: StatusTone): string {
  switch (tone) {
    case 'lime':
      return 'var(--acc-color)';
    case 'amber':
      return 'var(--warning-foreground)';
    case 'red':
      return 'var(--danger)';
  }
}

/** Expiry urgency color by days-left (prototype `expColorOf`), as a CSS var string. */
export function expiryHex(daysLeft: number | null): string {
  if (daysLeft === null || daysLeft === undefined) return 'var(--muted-foreground)';
  if (daysLeft <= 30) return 'var(--danger)';
  if (daysLeft <= 90) return 'var(--warning-foreground)';
  return 'var(--acc-color)';
}

/** The list filter segments. */
export const STATUS_FILTERS = ['All', 'Available', 'Allocated', 'Held'] as const;
export type StatusFilter = (typeof STATUS_FILTERS)[number];

/** Whether a group passes a given filter segment. */
export function passesFilter(status: StockStatus, filter: StatusFilter): boolean {
  switch (filter) {
    case 'All':
      return true;
    case 'Available':
      return status === 'Available';
    case 'Allocated':
      return status === 'Allocated';
    case 'Held':
      return status === 'Hold';
  }
}
