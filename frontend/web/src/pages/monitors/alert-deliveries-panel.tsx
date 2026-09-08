import { useState } from 'react';
import { toast } from 'sonner';
import { RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { useAlertDeliveries, useRedeliverAlertDelivery } from '@/pages/monitors/use-alert-deliveries';
import type { AlertDeliveryDto, AlertDeliveryStatus } from '@/pages/monitors/monitors-api';

const STATUS_FILTERS: Array<{ key: AlertDeliveryStatus | ''; label: string }> = [
  { key: '', label: 'All' },
  { key: 'FAILED', label: 'Failed' },
  { key: 'DEAD', label: 'Dead' },
];

/** Status chip color, design tokens only (no raw hex). */
const STATUS_CLASS: Record<AlertDeliveryStatus, string> = {
  PENDING: 'bg-muted text-muted-foreground',
  DELIVERED: 'bg-success text-success-foreground',
  FAILED: 'bg-warning text-warning-foreground',
  DEAD: 'bg-destructive/12 text-destructive',
};

/**
 * Row 34 (defect-burndown-4): "Recent deliveries", the `alert_deliveries` rows written by the
 * fan-out/relay, previously unreachable without raw SQL. Compact companion to `AlertFeed`:
 * status-filterable list with a per-row redeliver action for a stuck DEAD/FAILED row. Markup
 * template: `admin-integrations-page.tsx`'s deliveries block; API pattern:
 * `use-webhooks.ts`'s `useDeliveries`/`useRedeliver`.
 */
export function AlertDeliveriesPanel() {
  const [status, setStatus] = useState<AlertDeliveryStatus | ''>('');
  const deliveriesQuery = useAlertDeliveries(status || undefined);
  const redeliver = useRedeliverAlertDelivery();

  const deliveries: AlertDeliveryDto[] = deliveriesQuery.data ?? [];

  return (
    <section
      data-testid="alert-deliveries-panel"
      className="mt-5 rounded-2xl border border-border bg-card p-5"
    >
      <div className="mb-4 flex items-center justify-between gap-2">
        <h2 className="m-0 text-[16px] font-semibold text-foreground">Recent deliveries</h2>
        <div className="flex gap-1 rounded-[9px] border border-border bg-background p-[3px]">
          {STATUS_FILTERS.map((f) => (
            <button
              key={f.key || 'all'}
              type="button"
              onClick={() => setStatus(f.key)}
              className={cn(
                'rounded-[7px] px-3 py-1 text-[12px] font-semibold transition-colors',
                status === f.key
                  ? 'bg-primary text-primary-foreground'
                  : 'text-muted-foreground hover:text-foreground',
              )}
            >
              {f.label}
            </button>
          ))}
        </div>
      </div>

      {deliveriesQuery.isLoading && (
        <p className="m-0 text-[13px] text-muted-foreground">Loading deliveries…</p>
      )}

      {!deliveriesQuery.isLoading && deliveries.length === 0 && (
        <p className="m-0 text-[13px] text-muted-foreground">No deliveries yet.</p>
      )}

      <ul className="flex flex-col gap-1.5">
        {deliveries.map((d) => (
          <li
            key={d.id}
            className="flex items-center gap-3 rounded-lg border border-border bg-background px-3 py-2"
          >
            <span
              className={cn('rounded-full px-2 py-0.5 text-[10px] font-bold', STATUS_CLASS[d.status])}
            >
              {d.status}
            </span>
            <span className="numeric flex-1 truncate text-[12px] text-foreground/75">
              {d.channelKey}
            </span>
            <span className="numeric text-[11px] text-muted-foreground/70">{d.attempts}x</span>
            {(d.status === 'FAILED' || d.status === 'DEAD') && (
              <Button
                size="sm"
                variant="outline"
                onClick={() =>
                  redeliver.mutate(d.id, { onSuccess: () => toast.success('Re-queued') })
                }
                aria-label="Redeliver"
              >
                <RefreshCw className="size-3.5" />
              </Button>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
