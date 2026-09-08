import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useClock } from '@/hooks/use-clock';
import {
  getAlerts,
  getMonitors,
  patchMonitor,
  resolveAlert,
  type AlertDto,
  type BackendSeverity,
  type MonitorConfigUpdate,
  type MonitorDto,
} from './monitors-api';

/**
 * Data + view-state module for the Event Monitors screen (Screen 6).
 *
 * Backed by the real `karyo-monitors` module (Task 1-10): `GET/PATCH
 * /api/v1/monitors` + `GET /api/v1/alerts` + `POST /api/v1/alerts/{id}/{ack,resolve}`.
 * The whole screen is license-gated (`useLicense().isEntitled('monitors')`) —
 * see `monitors-page.tsx`, which renders a locked/upsell panel otherwise.
 *
 * Every control here (toggle, mute, apply fix, rule-builder mutators) is a
 * React Query mutation against `MonitorConfigUpdate`/alert endpoints; the
 * view types below (`Monitor`, `Severity`, `MonitorStatus`, ...) are kept
 * byte-for-byte compatible with the previous mock so `monitor-list.tsx`,
 * `firing-band.tsx` and `rule-builder.tsx` compile unchanged.
 */

export type Severity = 'Low' | 'Medium' | 'High';
export type Operator = '<' | '>';
export type MonitorStatus = 'firing' | 'healthy' | 'muted';

export interface Channels {
  push: boolean;
  email: boolean;
  slack: boolean;
}

export interface Monitor {
  id: string;
  name: string;
  metric: string;
  op: Operator;
  threshold: number;
  unit: string;
  step: number;
  scope: string;
  severity: Severity;
  enabled: boolean;
  status: MonitorStatus;
  last: string;
  ch: Channels;
  fired: string;
  fix: string;
  /** The open FIRING alert id backing this monitor, if any (used by `applyFix`). */
  alertId: number | null;
}

/* -------------------------------------------------------------------------- */
/* Color maps (from the prototype `renderVals()`)                             */
/* -------------------------------------------------------------------------- */

/** Severity → text color class. */
export const SEVERITY_TEXT: Record<Severity, string> = {
  Low: 'text-info',
  Medium: 'text-warning-foreground',
  High: 'text-destructive',
};

/** Severity → soft background (matches prototype rgba alphas). */
export const SEVERITY_BG: Record<Severity, string> = {
  Low: 'bg-[rgba(90,183,224,0.12)]',
  Medium: 'bg-[rgba(240,180,60,0.12)]',
  High: 'bg-[rgba(255,106,69,0.12)]',
};

/** Severity → accent hex (used for segmented-control inset ring). */
export const SEVERITY_HEX: Record<Severity, string> = {
  Low: '#5AB7E0',
  Medium: '#F0B43C',
  High: '#FF6A45',
};

/** Status dot color class. */
export const STATUS_DOT: Record<MonitorStatus, string> = {
  firing: 'bg-destructive',
  healthy: 'bg-primary',
  muted: 'bg-muted-foreground',
};

/** Status pill (builder) text + bg. */
export const STATUS_PILL_TEXT: Record<MonitorStatus, string> = {
  firing: 'text-destructive',
  healthy: 'text-primary',
  muted: 'text-muted-foreground',
};
export const STATUS_PILL_BG: Record<MonitorStatus, string> = {
  firing: 'bg-[rgba(255,106,69,0.12)]',
  healthy: 'bg-[rgba(199,242,78,0.1)]',
  muted: 'bg-accent',
};
export const STATUS_LABEL: Record<MonitorStatus, string> = {
  firing: 'FIRING',
  healthy: 'HEALTHY',
  muted: 'MUTED',
};

/** Effective status: a disabled monitor always reads MUTED. */
export function effectiveStatus(m: Monitor): MonitorStatus {
  return m.enabled ? m.status : 'muted';
}

/** Condition line: `metric op threshold unit`. */
export function conditionText(m: Monitor): string {
  return `${m.metric} ${m.op} ${m.threshold}${m.unit}`;
}

/* -------------------------------------------------------------------------- */
/* Backend <-> view mapping                                                   */
/* -------------------------------------------------------------------------- */

const SEVERITY_FROM_BACKEND: Record<BackendSeverity, Severity> = {
  LOW: 'Low',
  MEDIUM: 'Medium',
  HIGH: 'High',
};

const SEVERITY_TO_BACKEND: Record<Severity, BackendSeverity> = {
  Low: 'LOW',
  Medium: 'MEDIUM',
  High: 'HIGH',
};

/** Threshold step size per unit — the backend has no per-monitor step config. */
function stepFor(unit: string): number {
  if (unit === '%') return 5;
  return 1;
}

/** `"2m ago"` style relative time; `"—"` when there is no timestamp yet. */
function relativeTime(iso: string | null): string {
  if (!iso) return '—';
  const diffMs = Date.now() - new Date(iso).getTime();
  const mins = Math.round(diffMs / 60_000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.round(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.round(hours / 24);
  return `${days}d ago`;
}

function mapMonitor(dto: MonitorDto, firingAlert: AlertDto | undefined): Monitor {
  const status: MonitorStatus = dto.firing ? 'firing' : !dto.enabled ? 'muted' : 'healthy';
  return {
    id: dto.key,
    name: dto.name,
    metric: dto.metric,
    op: dto.op as Operator,
    threshold: dto.threshold,
    unit: dto.unit,
    step: stepFor(dto.unit),
    scope: dto.scope,
    severity: SEVERITY_FROM_BACKEND[dto.severity],
    enabled: dto.enabled,
    status,
    last: relativeTime(dto.lastFired),
    ch: dto.channels,
    fired: firingAlert?.reason ?? '',
    fix: firingAlert?.suggestedFix ?? '',
    alertId: firingAlert?.id ?? null,
  };
}

const EMPTY_MONITOR: Monitor = {
  id: '',
  name: '',
  metric: '',
  op: '>',
  threshold: 0,
  unit: '',
  step: 1,
  scope: '',
  severity: 'Medium',
  enabled: false,
  status: 'healthy',
  last: '—',
  ch: { push: false, email: false, slack: false },
  fired: '',
  fix: '',
  alertId: null,
};

/* -------------------------------------------------------------------------- */
/* Hook                                                                       */
/* -------------------------------------------------------------------------- */

export interface MonitorsController {
  clock: string;
  monitors: Monitor[];
  firing: Monitor[];
  selected: Monitor;
  activeCount: number;
  firingCount: number;
  mutedCount: number;
  select: (id: string) => void;
  toggleEnabled: (id: string) => void;
  /** Apply fix → resolve the monitor's open FIRING alert. */
  applyFix: (id: string) => void;
  /** Mute → disable the monitor. */
  mute: (id: string) => void;
  /** Builder mutators (write back to the selected monitor). */
  setOp: (op: Operator) => void;
  stepThreshold: (dir: 1 | -1) => void;
  setSeverity: (sev: Severity) => void;
  toggleChannel: (ch: keyof Channels) => void;
}

export function useMonitors(): MonitorsController {
  const clock = useClock();
  const queryClient = useQueryClient();
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const monitorsQuery = useQuery({
    queryKey: ['monitors'],
    queryFn: getMonitors,
    staleTime: 10_000,
  });
  const alertsQuery = useQuery({
    queryKey: ['alerts', 'FIRING'],
    queryFn: () => getAlerts('FIRING'),
    staleTime: 10_000,
  });

  const monitors = useMemo(() => {
    const monitorDtos = monitorsQuery.data ?? [];
    const alertDtos = alertsQuery.data ?? [];
    return monitorDtos.map((dto) => {
      const alert = alertDtos.find((a) => a.monitorKey === dto.key && a.status === 'FIRING');
      return mapMonitor(dto, alert);
    });
  }, [monitorsQuery.data, alertsQuery.data]);

  const selected = useMemo(() => {
    if (monitors.length === 0) return EMPTY_MONITOR;
    return monitors.find((m) => m.id === selectedId) ?? monitors[0];
  }, [monitors, selectedId]);

  const firing = useMemo(
    () => monitors.filter((m) => m.enabled && m.status === 'firing'),
    [monitors],
  );

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['monitors'] });
    queryClient.invalidateQueries({ queryKey: ['alerts'] });
  };

  const patchMutation = useMutation({
    mutationFn: ({ key, update }: { key: string; update: MonitorConfigUpdate }) =>
      patchMonitor(key, update),
    onSuccess: invalidate,
  });

  const resolveMutation = useMutation({
    mutationFn: (alertId: number) => resolveAlert(alertId),
    onSuccess: invalidate,
  });

  function patch(key: string, update: MonitorConfigUpdate) {
    if (!key) return;
    patchMutation.mutate({ key, update });
  }

  function patchSelected(update: MonitorConfigUpdate) {
    patch(selected.id, update);
  }

  return {
    clock,
    monitors,
    firing,
    selected,
    activeCount: monitors.filter((m) => m.enabled).length,
    firingCount: firing.length,
    mutedCount: monitors.filter((m) => !m.enabled).length,

    select: (id) => setSelectedId(id),

    toggleEnabled: (id) => {
      const m = monitors.find((mm) => mm.id === id);
      if (!m) return;
      patch(id, { enabled: !m.enabled });
    },

    applyFix: (id) => {
      const m = monitors.find((mm) => mm.id === id);
      if (m?.alertId != null) resolveMutation.mutate(m.alertId);
    },

    mute: (id) => patch(id, { enabled: false }),

    setOp: (op) => patchSelected({ op }),

    stepThreshold: (dir) =>
      patchSelected({ threshold: Math.max(0, selected.threshold + dir * selected.step) }),

    setSeverity: (severity) => patchSelected({ severity: SEVERITY_TO_BACKEND[severity] }),

    toggleChannel: (ch) => patchSelected({ channels: { ...selected.ch, [ch]: !selected.ch[ch] } }),
  };
}
