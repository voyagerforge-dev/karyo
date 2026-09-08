import { api } from '@/lib/api-client';

/** Backend `Severity` enum (`com.karyo.monitors.vo.Severity`). */
export type BackendSeverity = 'LOW' | 'MEDIUM' | 'HIGH';

/** Backend `AlertStatus` enum (`com.karyo.monitors.vo.AlertStatus`). FIRING → (ACK) → RESOLVED. */
export type AlertStatus = 'FIRING' | 'ACK' | 'RESOLVED';

export interface MonitorChannels {
  push: boolean;
  email: boolean;
  slack: boolean;
}

/** Catalog entry + this tenant's config + live status. Mirrors `MonitorDto`. */
export interface MonitorDto {
  key: string;
  name: string;
  metric: string;
  op: string;
  threshold: number;
  unit: string;
  severity: BackendSeverity;
  scope: string;
  enabled: boolean;
  channels: MonitorChannels;
  /** Any open FIRING/ACK alert for this monitor. */
  firing: boolean;
  /** Most recent alert `firstFiredAt`, or null if never fired. */
  lastFired: string | null;
}

/** PATCH body — all fields optional; only present fields are applied. Mirrors `MonitorConfigUpdate`. */
export interface MonitorConfigUpdate {
  enabled?: boolean;
  threshold?: number;
  severity?: BackendSeverity;
  op?: string;
  channels?: MonitorChannels;
}

/** Mirrors `AlertDto`. */
export interface AlertDto {
  id: number;
  monitorKey: string;
  monitorName: string;
  severity: BackendSeverity;
  status: AlertStatus;
  scope: string;
  reason: string;
  suggestedFix: string;
  observedValue: number;
  firstFiredAt: string;
  lastSeenAt: string;
  resolvedAt: string | null;
}

/** Backend `AlertDeliveryStatus` enum (`com.karyo.monitors.delivery.AlertDeliveryStatus`). */
export type AlertDeliveryStatus = 'PENDING' | 'FAILED' | 'DELIVERED' | 'DEAD';

/** Mirrors `AlertDeliveryDto` (row 34: one queued/attempted delivery of an alert through a channel). */
export interface AlertDeliveryDto {
  id: number;
  alertId: number;
  channelKey: string;
  status: AlertDeliveryStatus;
  attempts: number;
  nextAttemptAt: string;
  lastError: string | null;
  created: string;
}

export const getMonitors = () => api.get<MonitorDto[]>('/api/v1/monitors');

export const patchMonitor = (key: string, update: MonitorConfigUpdate) =>
  api.patch<MonitorDto>(`/api/v1/monitors/${key}`, update);

export const getAlerts = (status?: AlertStatus) =>
  api.get<AlertDto[]>(`/api/v1/alerts${status ? `?status=${status}` : ''}`);

export const ackAlert = (id: number) => api.post<AlertDto>(`/api/v1/alerts/${id}/ack`, {});

export const resolveAlert = (id: number) => api.post<AlertDto>(`/api/v1/alerts/${id}/resolve`, {});

export const getAlertDeliveries = (status?: AlertDeliveryStatus) =>
  api.get<AlertDeliveryDto[]>(`/api/v1/alert-deliveries${status ? `?status=${status}` : ''}`);

export const redeliverAlertDelivery = (id: number) =>
  api.post(`/api/v1/alert-deliveries/${id}/redeliver`, {});
