import type { EntityTone } from '@/components/master-detail/tones';
import type { LocationResponse } from '@/types/location';

/**
 * Derived view-model for a location — REAL fields only. Occupancy comes from
 * the backend `allocation` (a 0–100 percent it maintains); dimensions/weight
 * from `locationType`; status from lock + allocation. Phase B (B9/B10/B12)
 * added seeder-derived `capacity`/`temperatureZone`/`handlingClass`/`kind`/
 * `lastCountedAt` — real, deterministic fields (honest, not fabricated), each
 * falling back to "—" when null. L3 (locations-layout sprint) added real
 * `plcCode` (falls back to "—") and `excludedFromPutaway` (derived from
 * `allocationState !== 0`). Task 10 added `isClearing` (A2-1's singleton
 * clearing-location flag) straight through. `replenRule` is a genuine gap:
 * not modeled anywhere in the layout API, so it stays "—" — this module
 * fabricates NOTHING.
 */

export type LocationStatus = 'Active' | 'Blocked' | 'Empty';
export type LocationKind = 'Pick face' | 'Reserve' | 'Staging' | 'Bulk' | 'Floor' | 'Other';

const GAP = '—';

export interface LocationView {
  id: number;
  /** REAL: raw location name — the journal-query key, distinct from display `code`. */
  name: string;
  code: string;
  zoneLabel: string;
  zoneShort: string;
  bay: string;
  typeName: string;
  kind: LocationKind;
  status: LocationStatus;
  lockTypeName: string;
  lockType: number;
  pickSequence: number;
  /** REAL: occupancy percent from `allocation` (0–100). */
  occPct: number;
  /** REAL: locationType w×d×h, else "—". */
  dimensions: string;
  /** REAL: liftingCapacity, else "—". */
  maxWeight: string;
  /** REAL: seeder-derived `handlingClass` (e.g. "Hazmat"), else "—". */
  storageClass: string;
  /** REAL: seeder-derived `temperatureZone` (e.g. "Chilled"), else "—". */
  temperature: string;
  /** Honest gap ("—"): not modeled by the layout API. */
  replenRule: string;
  /** REAL: `lastCountedAt` from the seeded cycle-count history, else "—". */
  lastCounted: string;
  /** REAL: seeder-derived UL/pallet-slot capacity, else null (render "—"). */
  capacity: number | null;
  /** REAL: `plcCode` — free-text automation-system (PLC/WCS) address, else "—". */
  plcCode: string;
  /** REAL: `allocationState !== 0` — operator has excluded this location from the putaway finder. */
  excludedFromPutaway: boolean;
  /** REAL: `isClearing` — A2-1's singleton clearing-location flag. */
  isClearing: boolean;
  /** REAL: only the lock-derived block note (else null). */
  note: { text: string; tone: 'amber' | 'red' } | null;
}

/** Occupancy → Control palette tone. */
export function occTone(occPct: number, status: LocationStatus): EntityTone {
  if (status === 'Blocked') return 'red';
  if (status === 'Empty' || occPct === 0) return 'grey';
  if (occPct >= 95) return 'red';
  if (occPct >= 85) return 'amber';
  if (occPct >= 35) return 'lime';
  return 'blue';
}

function classifyKind(typeName: string): LocationKind {
  const t = typeName.toLowerCase();
  if (t.includes('pick') || t.includes('forward') || t.includes('face')) return 'Pick face';
  if (t.includes('reserve') || t.includes('pallet') || t.includes('rack') || t.includes('bulk')) return 'Reserve';
  if (t.includes('floor') || t.includes('ground')) return 'Floor';
  return 'Other';
}

/** Map the backend's real `kind` enum onto the display union; null/unknown falls through to the heuristic. */
function kindFromBackend(kind: string | null): LocationKind | null {
  switch (kind) {
    case 'PICK_FACE':
      return 'Pick face';
    case 'RESERVE':
      return 'Reserve';
    case 'STAGING':
      return 'Staging';
    case 'BULK':
      return 'Bulk';
    default:
      return null;
  }
}

/** Title-case an UPPER_SNAKE enum value (e.g. "HIGH_VALUE" -> "High Value"), else "—". */
function titleCase(value: string | null): string {
  if (!value) return GAP;
  return value
    .toLowerCase()
    .split('_')
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');
}

function formatDate(iso: string | null): string {
  if (!iso) return GAP;
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
}

/** Map a raw LocationResponse to the REAL-only view model. */
export function deriveLocation(loc: LocationResponse): LocationView {
  const code = loc.scanCode ?? loc.name;
  const zoneLabel = loc.zone?.name ?? loc.area?.name ?? 'Unzoned';
  const typeName = loc.locationType?.name ?? 'Location';
  const kind = kindFromBackend(loc.kind) ?? classifyKind(typeName);

  const zoneShort = (loc.rack ?? loc.zone?.name ?? code).toString().slice(0, 3).toUpperCase();
  const bay = (loc.field ?? loc.section ?? loc.name).toString().slice(0, 4).toUpperCase();

  const blocked = loc.lockType > 0;
  const occPct = Math.round(Number(loc.allocation));
  const status: LocationStatus = blocked ? 'Blocked' : occPct === 0 ? 'Empty' : 'Active';

  const lt = loc.locationType;
  const dimensions =
    lt?.width && lt?.depth && lt?.height ? `${lt.width}×${lt.depth}×${lt.height}` : GAP;
  const maxWeight = lt?.liftingCapacity ? `${lt.liftingCapacity} kg` : GAP;

  const note: LocationView['note'] = blocked
    ? {
        text: `Blocked for ${loc.lockTypeName || 'inspection'} — no picks will allocate here until released.`,
        tone: 'red',
      }
    : null;

  return {
    id: loc.id,
    name: loc.name,
    code,
    zoneLabel,
    zoneShort,
    bay,
    typeName,
    kind,
    status,
    lockTypeName: loc.lockTypeName,
    lockType: loc.lockType,
    pickSequence: loc.orderIndex,
    occPct,
    dimensions,
    maxWeight,
    storageClass: titleCase(loc.handlingClass),
    temperature: titleCase(loc.temperatureZone),
    replenRule: GAP,
    lastCounted: formatDate(loc.lastCountedAt),
    capacity: loc.capacity,
    plcCode: loc.plcCode ?? GAP,
    excludedFromPutaway: loc.allocationState !== 0,
    isClearing: loc.isClearing,
    note,
  };
}
