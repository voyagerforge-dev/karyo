import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import {
  ackAlert,
  getAlerts,
  resolveAlert,
  type AlertDto,
  type AlertStatus,
  type BackendSeverity,
} from '@/pages/monitors/monitors-api';

const STATUS_TABS: Array<{ key: AlertStatus; label: string }> = [
  { key: 'FIRING', label: 'Firing' },
  { key: 'ACK', label: 'Acknowledged' },
  { key: 'RESOLVED', label: 'Resolved' },
];

const SEVERITY_TEXT: Record<BackendSeverity, string> = {
  LOW: 'text-info',
  MEDIUM: 'text-warning-foreground',
  HIGH: 'text-destructive',
};

/**
 * Alert feed — status-tabbed list of alerts (FIRING/ACK/RESOLVED) with manual
 * ack/resolve actions. Independent of `use-monitors` (which only tracks the
 * FIRING subset needed to drive the monitor-list/firing-band view); this
 * component owns its own query so it can show the full lifecycle.
 */
export function AlertFeed() {
  const [status, setStatus] = useState<AlertStatus>('FIRING');
  const queryClient = useQueryClient();

  const alertsQuery = useQuery({
    queryKey: ['alerts', status],
    queryFn: () => getAlerts(status),
    staleTime: 10_000,
  });

  function invalidate() {
    queryClient.invalidateQueries({ queryKey: ['alerts'] });
    queryClient.invalidateQueries({ queryKey: ['monitors'] });
  }

  const ackMutation = useMutation({
    mutationFn: (id: number) => ackAlert(id),
    onSuccess: () => {
      invalidate();
      toast.success('Alert acknowledged');
    },
  });

  const resolveMutation = useMutation({
    mutationFn: (id: number) => resolveAlert(id),
    onSuccess: () => {
      invalidate();
      toast.success('Alert resolved');
    },
  });

  const alerts: AlertDto[] = alertsQuery.data ?? [];

  return (
    <section
      data-testid="alert-feed"
      className="mt-5 rounded-2xl border border-border bg-card p-5"
    >
      <div className="mb-4 flex items-center justify-between gap-2">
        <h2 className="m-0 text-[16px] font-semibold text-foreground">Alert feed</h2>
        <div className="flex gap-1 rounded-[9px] border border-border bg-background p-[3px]">
          {STATUS_TABS.map((tab) => (
            <button
              key={tab.key}
              type="button"
              onClick={() => setStatus(tab.key)}
              className={cn(
                'rounded-[7px] px-3 py-1 text-[12px] font-semibold transition-colors',
                status === tab.key
                  ? 'bg-primary text-primary-foreground'
                  : 'text-muted-foreground hover:text-foreground',
              )}
            >
              {tab.label}
            </button>
          ))}
        </div>
      </div>

      {alertsQuery.isLoading && (
        <p className="m-0 text-[13px] text-muted-foreground">Loading alerts…</p>
      )}

      {!alertsQuery.isLoading && alerts.length === 0 && (
        <p className="m-0 text-[13px] text-muted-foreground">
          No {STATUS_TABS.find((t) => t.key === status)?.label.toLowerCase()} alerts.
        </p>
      )}

      <ul className="flex flex-col gap-2.5">
        {alerts.map((a) => (
          <li
            key={a.id}
            className="flex items-start justify-between gap-3 rounded-xl border border-border bg-background p-3.5"
          >
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <span className={cn('text-[12px] font-bold', SEVERITY_TEXT[a.severity])}>
                  {a.severity}
                </span>
                <span className="text-[13px] font-semibold text-foreground">{a.monitorName}</span>
                <span className="text-[11px] text-muted-foreground">{a.scope}</span>
              </div>
              <p className="m-0 mt-1 text-[12.5px] text-foreground/75">{a.reason}</p>
            </div>
            <div className="flex flex-none gap-2">
              {a.status === 'FIRING' && (
                <Button size="sm" variant="outline" onClick={() => ackMutation.mutate(a.id)}>
                  Ack
                </Button>
              )}
              {a.status !== 'RESOLVED' && (
                <Button size="sm" onClick={() => resolveMutation.mutate(a.id)}>
                  Resolve
                </Button>
              )}
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
