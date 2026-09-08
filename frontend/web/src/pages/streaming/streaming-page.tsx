import { useState } from 'react';
import { AlertTriangle, Lock } from 'lucide-react';
import { FilterChips } from '@/components/master-detail/master-detail';
import type { FilterChipOption } from '@/components/master-detail/master-detail';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Button } from '@/components/ui/button';
import { usePermissions } from '@/hooks/use-permissions';
import { useLicense } from '@/features/license/use-license';
import {
  useStreamingStatus,
  useStreamingOrders,
  useRetryStreamingOrder,
} from '@/features/streaming/use-streaming';
import { ORDER_STATES } from '@/types/orders';
import type { StreamBucket, StreamingStrategyStatus, StreamOrder } from '@/types/streaming';

/**
 * Streaming screen (v2.x Advanced Fulfillment pack, Task 7): the desktop dashboard for
 * `karyo-streaming` (Task 6 backend). Gated behind the `advanced-fulfillment` license
 * entitlement -- same key as crossdock/waves. The gate renders BEFORE any data hook runs
 * (a separate `StreamingBoard` component mounts only once entitled), mirroring
 * `waves-page.tsx` / `forecasting-page.tsx` so an unentitled tenant never fires a 403.
 */

function LockedStreamingPanel() {
  return (
    <div
      data-testid="streaming-locked"
      className="flex flex-col items-center justify-center gap-3 rounded-2xl border border-border bg-card px-8 py-20 text-center"
    >
      <div className="flex size-12 items-center justify-center rounded-full bg-signal/10">
        <Lock className="size-5 text-primary" strokeWidth={2} />
      </div>
      <h1 className="font-display m-0 text-[20px] font-bold tracking-[-0.02em] text-foreground">
        Order streaming is a paid add-on
      </h1>
      <p className="m-0 max-w-md text-[13px] text-muted-foreground">
        Release orders continuously in micro-batches, push them to picking the moment stock is
        reserved, and see what is waiting or stalled. Contact your account team to enable
        Advanced Fulfillment for this tenant.
      </p>
    </div>
  );
}

function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) return '-';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '-' : d.toLocaleString();
}

/** The orders screens' own state names (`ORDER_STATES`), so one code never reads two ways.
 * A code outside that list (an order that raced past PROCESSABLE) falls back to the raw number. */
function stateLabel(state: number): string {
  return ORDER_STATES.find((s) => s.code === state)?.name ?? String(state);
}

const BUCKETS: ReadonlyArray<FilterChipOption<StreamBucket>> = [
  { value: 'WAITING', label: 'Waiting' },
  { value: 'ESCALATED', label: 'Escalated' },
  { value: 'STALLED', label: 'Stalled' },
  { value: 'PUSH_FAILED', label: 'Push failed' },
];

function StrategiesTable({ strategies }: { strategies: StreamingStrategyStatus[] }) {
  if (strategies.length === 0) {
    return (
      <div className="rounded-2xl border border-border bg-card p-8 text-center text-[13px] text-muted-foreground">
        No strategies stream yet
      </div>
    );
  }

  return (
    <div className="rounded-2xl border border-border bg-card">
      <Table data-testid="streaming-strategies-table">
        <TableHeader>
          <TableRow>
            <TableHead>Strategy</TableHead>
            <TableHead>Mode</TableHead>
            <TableHead>Batch / wait / abandon</TableHead>
            <TableHead className="text-right">Eligible</TableHead>
            <TableHead className="text-right">Waiting</TableHead>
            <TableHead className="text-right">Escalated</TableHead>
            <TableHead className="text-right">Stalled</TableHead>
            <TableHead className="text-right">Push failed</TableHead>
            <TableHead>Last flush</TableHead>
            <TableHead>Batches / released / pushed (1h)</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {strategies.map((s) => (
            <TableRow key={s.strategyId} data-testid={`streaming-strategy-row-${s.strategyId}`}>
              <TableCell className="font-medium">
                <span className="inline-flex items-center gap-1.5">
                  {s.strategyName}
                  {!s.timingStrategyResolved && (
                    <span title="timing strategy not resolved" className="inline-flex">
                      <AlertTriangle className="size-3.5 text-warning-foreground" />
                    </span>
                  )}
                </span>
              </TableCell>
              <TableCell>{s.releaseMode}</TableCell>
              <TableCell className="numeric">
                {s.batchSize} / {s.maxWaitSeconds}s / {s.abandonSeconds}s
              </TableCell>
              <TableCell className="numeric text-right">{s.eligible}</TableCell>
              <TableCell className="numeric text-right">{s.waiting}</TableCell>
              <TableCell className="numeric text-right">{s.escalated}</TableCell>
              <TableCell className="numeric text-right">{s.stalled}</TableCell>
              <TableCell className="numeric text-right">{s.pushFailed}</TableCell>
              <TableCell>{fmtDateTime(s.lastFlushAt)}</TableCell>
              <TableCell className="numeric">
                {s.batchesLastHour} / {s.releasedLastHour} / {s.pushedLastHour}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

function OrdersTable({
  bucket,
  orders,
  canRetry,
  onRetry,
  retryPending,
}: {
  bucket: StreamBucket;
  orders: StreamOrder[];
  canRetry: boolean;
  onRetry: (orderId: number) => void;
  retryPending: boolean;
}) {
  if (orders.length === 0) {
    return (
      <div className="rounded-2xl border border-border bg-card p-8 text-center text-[13px] text-muted-foreground">
        Nothing in this bucket
      </div>
    );
  }

  const showRetry = bucket === 'STALLED' && canRetry;

  return (
    <div className="rounded-2xl border border-border bg-card">
      <Table data-testid="streaming-orders-table">
        <TableHeader>
          <TableRow>
            <TableHead>Order #</TableHead>
            <TableHead>Customer</TableHead>
            <TableHead className="text-right">Prio</TableHead>
            <TableHead>State</TableHead>
            <TableHead>First attempt</TableHead>
            <TableHead className="text-right">Pending lines</TableHead>
            {showRetry && <TableHead />}
          </TableRow>
        </TableHeader>
        <TableBody>
          {orders.map((o) => (
            <TableRow key={o.orderId} data-testid={`streaming-order-row-${o.orderId}`}>
              <TableCell className="font-medium">{o.orderNumber}</TableCell>
              <TableCell>{o.customerName ?? '-'}</TableCell>
              <TableCell className="numeric text-right">{o.prio}</TableCell>
              <TableCell>{stateLabel(o.state)}</TableCell>
              <TableCell>{fmtDateTime(o.firstAttemptAt)}</TableCell>
              <TableCell className="numeric text-right">{o.pendingLineCount}</TableCell>
              {showRetry && (
                <TableCell>
                  <Button
                    size="sm"
                    variant="outline"
                    data-testid={`streaming-retry-${o.orderId}`}
                    disabled={retryPending}
                    onClick={() => onRetry(o.orderId)}
                  >
                    Retry
                  </Button>
                </TableCell>
              )}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

function StreamingBoard() {
  const { hasPermission } = usePermissions();
  const canRetry = hasPermission('fulfillment-write');

  const [bucket, setBucket] = useState<StreamBucket>('STALLED');

  const { data: status, isLoading: statusLoading } = useStreamingStatus();
  const { data: orders, isLoading: ordersLoading } = useStreamingOrders(bucket);
  const retry = useRetryStreamingOrder();

  return (
    <div className="space-y-4" data-testid="streaming-board">
      <div className="flex items-center gap-3">
        <h1 className="font-display text-2xl font-bold tracking-[-0.02em]">Streaming</h1>
        <span
          data-testid="streaming-enabled-pill"
          title="Toggle via system property karyo.streaming.enabled"
          className="inline-flex items-center rounded-full bg-card px-3 py-1 text-[12px] font-semibold text-muted-foreground"
        >
          {status?.enabled ? 'Enabled' : 'Disabled'}
        </span>
      </div>

      {statusLoading ? (
        <p className="text-[13px] text-muted-foreground">Loading…</p>
      ) : (
        <StrategiesTable strategies={status?.strategies ?? []} />
      )}

      <div className="flex items-center gap-4">
        <h2 className="text-lg font-semibold">Orders</h2>
        <div className="flex gap-1.5" data-testid="streaming-bucket-chips">
          <FilterChips options={BUCKETS} value={bucket} onChange={setBucket} />
        </div>
      </div>

      {ordersLoading ? (
        <p className="text-[13px] text-muted-foreground">Loading…</p>
      ) : (
        <OrdersTable
          bucket={bucket}
          orders={orders ?? []}
          canRetry={canRetry}
          onRetry={(orderId) => retry.mutate(orderId)}
          retryPending={retry.isPending}
        />
      )}
    </div>
  );
}

export function StreamingPage() {
  const license = useLicense();

  let body: React.ReactNode;
  if (license.isLoading) {
    body = <p className="text-[13px] text-muted-foreground">Loading…</p>;
  } else if (!license.isEntitled('advanced-fulfillment')) {
    body = <LockedStreamingPanel />;
  } else {
    body = <StreamingBoard />;
  }

  return <div data-testid="streaming-page">{body}</div>;
}
