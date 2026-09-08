import type { EntityTone } from '@/components/master-detail/tones';
import type { GoodsReceiptResponse } from '@/types/receiving';

export interface ReceiptStatusInfo {
  label: string;
  tone: EntityTone;
  open: boolean;
}

/** Receipt-accepted lock subset (backend ALLOWED_RECEIPT_LOCK_TYPES). */
export const RECEIPT_LOCK_LABELS: Record<number, string> = {
  1: 'GENERAL',
  103: 'QUALITY FAULT',
  202: 'LOT EXPIRED',
  203: 'LOT TOO YOUNG',
};

export function getReceiptStatus(
  r: Pick<GoodsReceiptResponse, 'state' | 'pausedAt'>,
): ReceiptStatusInfo {
  const open = r.state === 50 || r.state === 500;
  if (open && r.pausedAt) return { label: 'Paused', tone: 'amber', open };
  if (r.state === 50) return { label: 'Created', tone: 'grey', open };
  if (r.state === 500) return { label: 'Receiving', tone: 'lime', open };
  if (r.state === 700) return { label: 'Finished', tone: 'blue', open: false };
  return { label: 'Canceled', tone: 'red', open: false };
}

export type ReceiptFilter = 'all' | 'open' | 'paused' | 'done';

export function matchesReceiptFilter(
  r: Pick<GoodsReceiptResponse, 'state' | 'pausedAt'>,
  filter: ReceiptFilter,
): boolean {
  if (filter === 'all') return true;
  const s = getReceiptStatus(r);
  if (filter === 'paused') return s.label === 'Paused';
  if (filter === 'open') return s.open && s.label !== 'Paused';
  return !s.open;
}
