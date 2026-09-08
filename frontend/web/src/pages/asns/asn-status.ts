import type { EntityTone } from '@/components/master-detail/tones';
import type { AsnResponse } from '@/types/receiving';

export interface AsnStatusInfo {
  label: string;
  tone: EntityTone;
}

export function getAsnStatus(a: Pick<AsnResponse, 'state'>): AsnStatusInfo {
  switch (a.state) {
    case 50:
      return { label: 'Created', tone: 'grey' };
    case 100:
      return { label: 'Released', tone: 'amber' };
    case 500:
      return { label: 'Receiving', tone: 'lime' };
    case 700:
      return { label: 'Finished', tone: 'blue' };
    case 800:
      return { label: 'Canceled', tone: 'red' };
    default:
      return { label: 'Unknown', tone: 'grey' };
  }
}

export type AsnFilter = 'all' | 'created' | 'receiving' | 'done';

export function matchesAsnFilter(
  a: Pick<AsnResponse, 'state'>,
  filter: AsnFilter,
): boolean {
  if (filter === 'all') return true;
  if (filter === 'created') return a.state === 50;
  if (filter === 'receiving') return a.state === 100 || a.state === 500;
  if (filter === 'done') return a.state === 700 || a.state === 800;
  return true;
}
