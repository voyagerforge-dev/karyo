import { useMemo, useState } from 'react';
import { toast } from 'sonner';
import { Ban, MoreHorizontal, PackageCheck, Send, Sparkles } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Skeleton } from '@/components/ui/skeleton';
import { TONE_COLOR } from '@/components/master-detail/tones';
import { viewPdf, saveZpl, archiveDocument } from '@/lib/document-actions';
import { useAuth } from '@/components/auth/auth-provider';
import { usePermissions } from '@/hooks/use-permissions';
import {
  useDeliveryOrder,
  useReleaseOrder,
  useCancelOrder,
  useClaimOrder,
  useReleaseOperator,
} from './use-orders';
import { useOrderActivity, recordTypeMeta } from '@/features/insights/use-journals';
import { useReleaseToPicking } from '@/features/picking/use-pick-orders';
import { OrderPipeline } from './order-pipeline';
import { getOrderStatus } from './order-status';
import { ORDER_STATE, type DeliveryOrderResponse } from '@/types/orders';

interface OrderDetailProps {
  /** Row data from the list (used for instant render while detail loads). */
  summary: DeliveryOrderResponse;
  /** Whether the user holds order-write (release / header actions). */
  canWrite: boolean;
}

function fmtDate(iso: string | null | undefined): string {
  if (!iso) return 'pending';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? 'pending' : d.toLocaleDateString();
}

function fmtTime(iso: string | null | undefined): string {
  if (!iso) return 'pending';
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? 'pending'
    : d.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

function seam(label: string) {
  toast.info(`${label} — not wired yet`);
}

/** Build the two ship-to address lines from the order's address fields. */
function shipToLines(o: DeliveryOrderResponse): { street: string; city: string } {
  const street = [o.street, o.streetNumber].filter(Boolean).join(' ').trim();
  const cityZip = [o.city, o.zipCode].filter(Boolean).join(' ').trim();
  const city = [cityZip, o.country].filter(Boolean).join(', ');
  return { street, city };
}

/** A KPI mini tile (Lines / Units / Value / Ship-by). */
function KpiTile({
  label,
  value,
  danger,
}: {
  label: string;
  value: string;
  danger?: boolean;
}) {
  return (
    <div
      className={cn(
        'flex-1 rounded-[13px] border bg-card p-[14px_16px]',
        danger ? 'border-destructive' : 'border-border',
      )}
    >
      <div className="text-[10px] font-semibold uppercase tracking-[0.1em] text-muted-foreground/70">
        {label}
      </div>
      <div
        className={cn(
          'numeric mt-1.5 text-[20px] font-bold',
          danger ? 'text-destructive' : 'text-foreground',
        )}
      >
        {value}
      </div>
    </div>
  );
}

/**
 * Orders detail-as-workspace (README #11): header + actions, fulfillment
 * pipeline, KPI tiles, contextual Copilot exception strip, line items table,
 * and a Ship-to / Activity side rail.
 */
export function OrderDetail({ summary, canWrite }: OrderDetailProps) {
  // Detail fetch enriches lines; fall back to the list row while loading.
  const { data: fetched, isLoading } = useDeliveryOrder(summary.id);
  const order = fetched ?? summary;

  const releaseMutation = useReleaseOrder();
  const releaseToPicking = useReleaseToPicking();
  const cancelMutation = useCancelOrder();
  const claimMutation = useClaimOrder();
  const releaseOperatorMutation = useReleaseOperator();
  const [confirmCancel, setConfirmCancel] = useState(false);

  const { userName } = useAuth();
  const { hasPermission } = usePermissions();
  const isManager = hasPermission('MANAGER');
  // Row 10: claim is pure metadata (never coupled to state) -- mirrors receiving's
  // ReceiptDetail claim/release affordance exactly.
  const isClaimOwner = order.operatorId != null && order.operatorId === userName;
  const isClaimedByOther = order.operatorId != null && !isClaimOwner;
  const canClaim = order.operatorId == null;
  const isClaimMutating = claimMutation.isPending || releaseOperatorMutation.isPending;

  const status = useMemo(() => getOrderStatus(order), [order]);

  const totalUnits = useMemo(
    () => order.lines.reduce((sum, l) => sum + l.amount, 0),
    [order.lines],
  );
  // B7: order value = Σ(amount × unitPrice) from the real per-line seeded price.
  // Only an honest "—" when every line is un-priced; a partial mix still sums
  // (unpriced lines contribute 0, matching the backend's honest-null contract).
  const allLinesUnpriced = order.lines.every((l) => l.unitPrice == null);
  const orderValue = useMemo(
    () => order.lines.reduce((sum, l) => sum + l.amount * (l.unitPrice ?? 0), 0),
    [order.lines],
  );
  const orderValueLabel = allLinesUnpriced
    ? '—'
    : orderValue.toLocaleString('en-US', { style: 'currency', currency: 'USD' });
  const shortLines = order.lines.filter((l) => l.shortage > 0);
  const shipTo = shipToLines(order);
  const hasAddress = shipTo.street !== '' || shipTo.city !== '';

  const { data: activity, isLoading: activityLoading } = useOrderActivity(order.orderNumber);

  // Ship-by at-risk seam: no SLA in the backend, so an undated order with an
  // open exception is flagged at-risk to demonstrate the red KPI behavior.
  const shipByAtRisk = status.exception && !order.deliveryDate;

  // B8b pipeline timestamps: Placed = order.created and Shipped = order.shippedAt
  // are both real. Released/Picking/Packed stay an honest "—" — no per-stage
  // history exists in the backend (do not fabricate intermediate times).
  const pipelineTimes: string[] = [
    fmtTime(order.created),
    '—',
    '—',
    '—',
    order.shippedAt ? fmtTime(order.shippedAt) : '—',
  ];

  // Order streaming (B3): API-only override + the three tier stamps, read-only here (no
  // order-form field -- ruling 4). Shown only when at least one of the four is set.
  const hasStreamStatus =
    !!order.releaseModeOverride ||
    !!order.streamFirstAttemptAt ||
    !!order.streamEscalatedAt ||
    !!order.streamStalledAt;

  const isCreated = order.state === ORDER_STATE.CREATED;
  const isProcessable = order.state === ORDER_STATE.PROCESSABLE;

  // Primary action by stage — real where the backend supports it.
  const canRelease = canWrite && isCreated;
  const canPickRelease = isProcessable; // release-to-picking is the real fulfillment hop

  // Backend gate: OrderState.canAdvanceTo(CANCELED) allows cancel strictly below
  // PICKED(600) and 409s otherwise -- mirror it exactly so the button never 4xxs.
  const isCancelable =
    canWrite && order.state < ORDER_STATE.PICKED && order.state !== ORDER_STATE.CANCELED;

  return (
    <div className="rounded-2xl">
      {/* Header */}
      <div className="mb-[18px] flex items-start justify-between gap-4">
        <div className="min-w-0">
          <div className="flex items-center gap-3">
            <h1 className="font-display numeric text-[25px] font-bold tracking-[-0.01em] text-foreground">
              {order.orderNumber}
            </h1>
            <span
              className="rounded-[20px] px-2.5 py-[3px] text-[11.5px] font-bold"
              style={{
                color: TONE_COLOR[status.tone],
                background: `${TONE_COLOR[status.tone]}22`,
              }}
              data-testid="order-detail-status"
            >
              {status.label}
            </span>
            {order.prio < 50 && (
              <span className="numeric rounded-md bg-error px-2 py-0.5 text-[10px] font-bold text-destructive">
                PRIORITY
              </span>
            )}
          </div>
          <p className="mt-[7px] truncate text-[13.5px] text-foreground/70">
            {order.customerName ?? 'No customer'} · created {fmtDate(order.created)}
          </p>
        </div>
        <div className="flex flex-none gap-2">
          {canRelease && (
            <button
              type="button"
              onClick={() => releaseMutation.mutate(order.id)}
              disabled={releaseMutation.isPending}
              className="h-9 rounded-[9px] bg-primary px-3.5 text-[13px] font-bold text-primary-foreground disabled:opacity-60"
            >
              {releaseMutation.isPending ? 'Releasing…' : 'Release'}
            </button>
          )}
          {canPickRelease && (
            <button
              type="button"
              onClick={() => releaseToPicking.mutate(order.id)}
              disabled={releaseToPicking.isPending}
              data-testid="release-to-picking-btn"
              className="flex h-9 items-center gap-1.5 rounded-[9px] bg-primary px-3.5 text-[13px] font-bold text-primary-foreground disabled:opacity-60"
            >
              <PackageCheck className="size-4" />
              {releaseToPicking.isPending ? 'Releasing…' : 'Release to picking'}
            </button>
          )}
          {!canRelease && !canPickRelease && (
            <button
              type="button"
              onClick={() => seam('Allocate')}
              className="flex h-9 items-center gap-1.5 rounded-[9px] bg-primary px-3.5 text-[13px] font-bold text-primary-foreground"
            >
              <Send className="size-4" />
              Allocate
            </button>
          )}
          <button
            type="button"
            onClick={() => seam('Print docs')}
            className="h-9 rounded-[9px] border border-border bg-card px-3.5 text-[13px] font-medium text-foreground hover:bg-accent"
          >
            Print docs
          </button>
          {/* Read-only document — no write-perm gate, mirrors the backend's
              order-read requirement. Visible only once the order has reached
              a state the delivery note can honestly reconcile (>= PICKED),
              matching the backend's DocumentNotReady(409) gate exactly. */}
          {order.state >= ORDER_STATE.PICKED && (
            <>
              <button
                type="button"
                data-testid="doc-delivery-note-btn"
                onClick={() => viewPdf(order.documentUrl)}
                className="h-9 rounded-[9px] border border-border bg-card px-3.5 text-[13px] font-medium text-foreground hover:bg-accent"
              >
                Delivery note
              </button>
              <button
                type="button"
                data-testid="doc-delivery-note-archive-btn"
                onClick={() => archiveDocument(order.documentUrl)}
                className="h-9 rounded-[9px] border border-border bg-card px-3.5 text-[13px] font-medium text-foreground hover:bg-accent"
              >
                Archive
              </button>
            </>
          )}
          {/* Row 10: derived from ShipmentLookup -- present only once a shipment (with a
              shipping unit) exists; a link that could 404 is never rendered. */}
          {order.labelUrl && (
            <button
              type="button"
              data-testid="doc-label-btn"
              onClick={() => saveZpl(order.labelUrl!, `${order.orderNumber}-label.zpl`)}
              className="h-9 rounded-[9px] border border-border bg-card px-3.5 text-[13px] font-medium text-foreground hover:bg-accent"
            >
              Label
            </button>
          )}
          <button
            type="button"
            onClick={() => seam('Hold')}
            className="h-9 rounded-[9px] border border-border bg-card px-3.5 text-[13px] font-medium text-foreground hover:bg-accent"
          >
            Hold
          </button>
          <button
            type="button"
            onClick={() => seam('More actions')}
            aria-label="More actions"
            className="flex size-9 flex-none items-center justify-center rounded-[9px] border border-border bg-card text-muted-foreground hover:text-foreground"
          >
            <MoreHorizontal className="size-4" />
          </button>
          {isCancelable && (
            <Button
              variant="outline"
              className="text-destructive"
              onClick={() => setConfirmCancel(true)}
              disabled={cancelMutation.isPending}
              data-testid="order-cancel-button"
            >
              <Ban className="size-4" />
              Cancel
            </Button>
          )}
        </div>
      </div>

      {/* Pipeline */}
      <OrderPipeline stageIndex={status.stageIndex} timestamps={pipelineTimes} />

      {/* KPI mini tiles */}
      <div className="mb-4 flex gap-3">
        <KpiTile label="Lines" value={String(order.lines.length)} />
        <KpiTile label="Units" value={totalUnits.toFixed(0)} />
        {/* Value = Σ(amount × unitPrice) from the real per-line seeded price (B7). */}
        <KpiTile label="Value" value={orderValueLabel} />
        <KpiTile
          label="Ship by"
          value={order.deliveryDate ? fmtDate(order.deliveryDate) : 'TBD'}
          danger={shipByAtRisk}
        />
      </div>

      {/* Copilot exception strip — only when an order line is short */}
      {shortLines.length > 0 && (
        <div
          className="mb-4 flex items-center gap-3 rounded-[13px] border border-destructive/30 bg-gradient-to-r from-[var(--error)] to-card p-[13px_16px]"
          data-testid="copilot-exception"
        >
          <div className="flex size-[26px] flex-none items-center justify-center rounded-lg bg-primary">
            <Sparkles className="size-3.5 text-primary-foreground" />
          </div>
          <div className="min-w-0 flex-1">
            <div className="text-[13px] font-semibold text-foreground">
              Line {shortLines[0].itemDataNumber} short by{' '}
              {shortLines[0].shortage.toFixed(0)} units
            </div>
            <div className="mt-0.5 text-[12px] text-foreground/75">
              Copilot: substitute an equivalent item or backorder the shortfall.
            </div>
          </div>
          <button
            type="button"
            onClick={() => seam('Substitute')}
            className="h-8 rounded-lg bg-primary px-3.5 text-[12.5px] font-bold text-primary-foreground"
          >
            Substitute
          </button>
          <button
            type="button"
            onClick={() => seam('Backorder')}
            className="h-8 rounded-lg border border-destructive/20 px-3 text-[12.5px] font-medium text-muted-foreground hover:text-foreground"
          >
            Backorder
          </button>
          <button
            type="button"
            onClick={() => toast.success('Exception dismissed')}
            className="h-8 rounded-lg border border-destructive/20 px-3 text-[12.5px] font-medium text-muted-foreground hover:text-foreground"
          >
            Dismiss
          </button>
        </div>
      )}

      {/* Lines + side rail */}
      <div className="flex flex-col items-start gap-4 lg:flex-row">
        {/* Line items */}
        <section className="w-full min-w-0 flex-[1.7] overflow-hidden rounded-2xl border border-border bg-card">
          <div className="flex items-center justify-between border-b border-border p-[14px_18px]">
            <h2 className="text-[14px] font-semibold text-foreground">Line items</h2>
            <span className="numeric text-[10.5px] text-muted-foreground/70">
              ORDERED · PICKED · STATUS
            </span>
          </div>
          {isLoading && !fetched ? (
            <div className="space-y-2 p-[14px_18px]">
              <Skeleton className="h-10 w-full" />
              <Skeleton className="h-10 w-full" />
            </div>
          ) : order.lines.length === 0 ? (
            <p className="p-[18px] text-[13px] text-muted-foreground">No lines on this order.</p>
          ) : (
            <table className="w-full" data-testid="order-lines-table">
              <tbody>
                {order.lines.map((line) => {
                  // Real derived rollup (PickRollupLookup, PICKED-state picks only;
                  // substitution split out because substitute picks carry a
                  // different SKU under the same line). reservedAmount is a
                  // reservation, not a pick — it no longer proxies progress.
                  const done = line.pickedAmount + line.substitutedAmount;
                  const pct =
                    line.amount > 0 ? Math.min(100, Math.round((done / line.amount) * 100)) : 0;
                  const isShort = line.shortage > 0;
                  const isPicked = done >= line.amount && line.amount > 0;
                  const inProgress = done > 0 && !isPicked;
                  const tone: keyof typeof TONE_COLOR = isShort
                    ? 'red'
                    : isPicked
                      ? 'lime'
                      : 'grey';
                  const statusLabel = isShort
                    ? 'Short'
                    : isPicked
                      ? 'Picked'
                      : inProgress
                        ? 'Picking'
                        : 'Pending';
                  return (
                    <tr
                      key={line.id}
                      className="border-b border-border last:border-0"
                      data-testid={`order-line-row-${line.lineNumber}`}
                    >
                      <td className="p-[12px_18px] align-middle">
                        <div className="text-[12.5px] font-semibold text-foreground">
                          {line.itemDataName ?? line.itemDataNumber}
                        </div>
                        <div className="mt-0.5 truncate text-[12px] text-muted-foreground">
                          {line.itemDataName && (
                            <span className="numeric">{line.itemDataNumber} · </span>
                          )}
                          {line.lotNumber ? `Lot ${line.lotNumber}` : `Line ${line.lineNumber}`}
                        </div>
                        {line.externalNumber && (
                          <div className="numeric mt-0.5 text-[11px] text-muted-foreground/70">
                            {line.externalNumber}
                          </div>
                        )}
                        {line.substitutedAmount > 0 && (
                          <div className="numeric mt-0.5 text-[11px] text-warning-foreground">
                            ({line.substitutedAmount} sub)
                          </div>
                        )}
                      </td>
                      <td className="p-[12px_8px] text-right align-middle whitespace-nowrap">
                        <div className="numeric text-[13px] font-bold text-foreground">
                          {`${done.toFixed(0)} / ${line.amount.toFixed(0)}`}
                        </div>
                        {line.reservedAmount > 0 && (
                          <div className="numeric text-[10.5px] text-muted-foreground/70">
                            {`res ${line.reservedAmount.toFixed(0)}`}
                          </div>
                        )}
                      </td>
                      <td className="w-[28%] p-[12px_18px] align-middle">
                        <div className="flex items-center gap-2">
                          <div className="h-1.5 flex-1 overflow-hidden rounded bg-background">
                            <div
                              className="h-full rounded"
                              style={{ width: `${pct}%`, background: TONE_COLOR[tone] }}
                            />
                          </div>
                          <span className="numeric text-[11px] text-muted-foreground">{`${pct}%`}</span>
                        </div>
                      </td>
                      <td className="p-[12px_18px] text-right align-middle">
                        <span
                          className="rounded-[20px] px-2.5 py-0.5 text-[11px] font-bold"
                          style={{
                            color: TONE_COLOR[tone],
                            background: `${TONE_COLOR[tone]}22`,
                          }}
                        >
                          {statusLabel}
                        </span>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </section>

        {/* Side rail */}
        <div className="flex w-full min-w-0 flex-1 flex-col gap-4">
          {/* Ship to — address is real; carrier/service/tracking are wired via the ShipmentLookup
              SPI (DeliveryOrderResponse.carrierName/carrierService/trackingNumber/shippedAt).
              They stay null — rendered "—" — until a shipment exists for this order. */}
          <section className="rounded-2xl border border-border bg-card p-[18px]">
            <h2 className="mb-3.5 text-[14px] font-semibold text-foreground">Ship to</h2>
            <div className="text-[13px] leading-[1.55] text-foreground/90">
              {order.customerName ?? 'No customer'}
              {hasAddress ? (
                <>
                  {shipTo.street && (
                    <>
                      <br />
                      <span className="text-muted-foreground">{shipTo.street}</span>
                    </>
                  )}
                  {shipTo.city && (
                    <>
                      <br />
                      <span className="text-muted-foreground">{shipTo.city}</span>
                    </>
                  )}
                </>
              ) : (
                <>
                  <br />
                  <span className="text-muted-foreground">Address not on file</span>
                </>
              )}
            </div>
            <div className="my-[15px] h-px bg-border" />
            {order.phone && (
              <div className="mb-2.5 flex justify-between">
                <span className="text-[12.5px] text-muted-foreground">Phone</span>
                <span className="text-[12.5px] font-semibold text-foreground/90">{order.phone}</span>
              </div>
            )}
            {order.email && (
              <div className="mb-2.5 flex justify-between">
                <span className="text-[12.5px] text-muted-foreground">Email</span>
                <span className="numeric text-[12.5px] text-foreground/90">{order.email}</span>
              </div>
            )}
            <div className="mb-2.5 flex justify-between">
              <span className="text-[12.5px] text-muted-foreground">Carrier</span>
              <span className="text-[12.5px] font-semibold text-foreground/90">{order.carrierName ?? '—'}</span>
            </div>
            <div className="mb-2.5 flex justify-between">
              <span className="text-[12.5px] text-muted-foreground">Service</span>
              <span className="text-[12.5px] font-semibold text-foreground/90">{order.carrierService ?? '—'}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-[12.5px] text-muted-foreground">Tracking</span>
              <span className="numeric text-[12px] text-info">{order.trackingNumber ?? '—'}</span>
            </div>
          </section>

          {/* Row 10: which StorageLocation this order's work is bound for (NOT the ship-to
              address above), the outbound sender, and the operator claim -- pure metadata,
              never coupled to order state. Mirrors ReceiptDetail's claim/release affordance. */}
          <section className="rounded-2xl border border-border bg-card p-[18px]" data-testid="order-assignment">
            <h2 className="mb-3.5 text-[14px] font-semibold text-foreground">Assignment</h2>
            <div className="mb-2.5 flex justify-between">
              <span className="text-[12.5px] text-muted-foreground">Destination</span>
              <span className="text-[12.5px] font-semibold text-foreground/90">
                {order.destinationLocationName ?? '—'}
              </span>
            </div>
            <div className="mb-2.5 flex justify-between">
              <span className="text-[12.5px] text-muted-foreground">Sender</span>
              <span className="text-[12.5px] font-semibold text-foreground/90">{order.senderName ?? '—'}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-[12.5px] text-muted-foreground">Operator (claim)</span>
              <span className="text-[12.5px] font-semibold text-foreground/90">{order.operatorId ?? '—'}</span>
            </div>
            {canWrite && (
              <div className="mt-3.5 flex flex-wrap items-center gap-2 border-t border-border pt-3.5">
                {canClaim && (
                  <Button
                    size="sm"
                    onClick={() => claimMutation.mutate(order.id)}
                    disabled={isClaimMutating}
                    data-testid="order-claim-button"
                  >
                    Claim
                  </Button>
                )}
                {isClaimOwner && (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => releaseOperatorMutation.mutate(order.id)}
                    disabled={isClaimMutating}
                    data-testid="order-release-operator-button"
                  >
                    Release claim
                  </Button>
                )}
                {isClaimedByOther && isManager && (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => releaseOperatorMutation.mutate(order.id)}
                    disabled={isClaimMutating}
                    data-testid="order-release-operator-button"
                  >
                    Release (manager)
                  </Button>
                )}
              </div>
            )}
          </section>

          {/* Order streaming (B3): read-only override + tier stamps; API-only, no order-form
              field. Rendered only when at least one of the four is set (an order that has
              never touched streaming shows nothing here). */}
          {hasStreamStatus && (
            <section
              className="rounded-2xl border border-border bg-card p-[18px]"
              data-testid="order-stream-status"
            >
              <h2 className="mb-3.5 text-[14px] font-semibold text-foreground">Streaming</h2>
              {order.releaseModeOverride && (
                <div className="mb-2.5 flex justify-between">
                  <span className="text-[12.5px] text-muted-foreground">Release mode override</span>
                  <span className="text-[12.5px] font-semibold text-foreground/90">
                    {order.releaseModeOverride}
                  </span>
                </div>
              )}
              {order.streamFirstAttemptAt && (
                <div className="mb-2.5 flex justify-between">
                  <span className="text-[12.5px] text-muted-foreground">First attempt</span>
                  <span className="numeric text-[12.5px] font-semibold text-foreground/90">
                    {fmtTime(order.streamFirstAttemptAt)}
                  </span>
                </div>
              )}
              {order.streamEscalatedAt && (
                <div className="mb-2.5 flex justify-between">
                  <span className="text-[12.5px] text-muted-foreground">Escalated</span>
                  <span className="numeric text-[12.5px] font-semibold text-foreground/90">
                    {fmtTime(order.streamEscalatedAt)}
                  </span>
                </div>
              )}
              {order.streamStalledAt && (
                <div className="flex justify-between">
                  <span className="text-[12.5px] text-muted-foreground">Stalled</span>
                  <span className="numeric text-[12.5px] font-semibold text-destructive">
                    {fmtTime(order.streamStalledAt)}
                  </span>
                </div>
              )}
            </section>
          )}

          {/* Activity — created/modified header is always real; below it, the real
              inventory-journal feed correlated to this order (B8a). Honest caveat:
              in the demo seeder only pick events carry correlationId=orderNumber
              (see HistoryGenerator.buildPicks) — receipts key on the ASN number
              instead, so an order that hasn't been picked yet shows no rows below
              the header even though the order itself exists. */}
          <section className="rounded-2xl border border-border bg-card p-[18px]">
            <h2 className="mb-4 text-[14px] font-semibold text-foreground">Activity</h2>
            <ActivityRow
              dot={TONE_COLOR[status.tone]}
              text={`Order ${status.label.toLowerCase()}`}
              meta={`${fmtTime(order.modified)} · last updated`}
            />
            <ActivityRow
              dot={TONE_COLOR.blue}
              text="Order placed"
              meta={`${fmtTime(order.created)} · ${order.customerName ?? 'channel'}`}
              last={activity.length === 0}
            />
            {activityLoading && activity.length === 0 && (
              <p className="pt-1 text-[12px] text-muted-foreground/70" data-testid="order-activity-loading">
                Loading activity…
              </p>
            )}
            {!activityLoading && activity.length === 0 && (
              <p className="pt-1 text-[12px] text-muted-foreground/70" data-testid="order-activity-empty">
                No recorded activity yet.
              </p>
            )}
            {activity.map((event, i) => {
              const meta = recordTypeMeta(event.recordType);
              return (
                <ActivityRow
                  key={`${event.recordType}-${event.created}-${i}`}
                  dot={meta.dotHex}
                  text={`${meta.label}${event.amount != null ? ` ${event.amount}` : ''}`}
                  meta={`${fmtTime(event.created)}${event.correlationId ? ` · ${event.correlationId}` : ''}`}
                  last={i === activity.length - 1}
                />
              );
            })}
          </section>
        </div>
      </div>

      <AlertDialog open={confirmCancel} onOpenChange={setConfirmCancel}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Cancel order?</AlertDialogTitle>
            <AlertDialogDescription>
              This will cancel &quot;{order.orderNumber}&quot; and release its stock reservations.
              This cannot be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Keep order</AlertDialogCancel>
            <AlertDialogAction
              onClick={() =>
                cancelMutation.mutate(order.id, { onSuccess: () => setConfirmCancel(false) })
              }
            >
              Cancel order
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

function ActivityRow({
  dot,
  text,
  meta,
  last,
}: {
  dot: string;
  text: string;
  meta: string;
  last?: boolean;
}) {
  return (
    <div className="flex gap-3 pb-3.5 last:pb-0">
      <div className="flex flex-none flex-col items-center">
        <span className="mt-[3px] size-[9px] rounded-full" style={{ background: dot }} />
        {!last && <span className="mt-[3px] w-0.5 flex-1 bg-border" />}
      </div>
      <div className="flex-1">
        <div className="text-[12.5px] leading-[1.4] text-foreground/85">{text}</div>
        <div className="numeric mt-0.5 text-[10.5px] text-muted-foreground/70">{meta}</div>
      </div>
    </div>
  );
}
