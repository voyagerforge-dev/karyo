import { Fragment, useEffect, useState } from 'react';
import { toast } from 'sonner';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { SectionCard } from '@/components/control/section-card';
import { StatusPill } from '@/components/control/status-pill';
import type { EntityTone } from '@/components/master-detail/tones';
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
import { pickStateName, pickStateVariant } from '@/lib/pick-state';
import { viewPdf, archiveDocument } from '@/lib/document-actions';
import { PickConfirmForm } from './pick-confirm-form';
import {
  useBulkConfirm,
  useBulkLines,
  useCancelPickOrder,
  useConfirmPick,
  usePickOrder,
} from '@/features/picking/use-pick-orders';
import { useDeliveryOrder } from '@/pages/orders/use-orders';
import { usePermissions } from '@/hooks/use-permissions';
import { refSourceId, type WorkItemResponse } from '@/types/work';

const PICKED = 600;

export interface PickPaneProps {
  workItem: WorkItemResponse;
}

/** PickOrderState -> pill tone. Mirrors TransportPane's stateTone helper. */
function pickPillTone(state: number): EntityTone {
  switch (state) {
    case 600:
      return 'lime'; // PICKED
    case 800:
      return 'red'; // CANCELED
    case 500:
      return 'amber'; // STARTED
    default:
      return 'grey'; // CREATED / RELEASED
  }
}

/**
 * PICK work-item detail (P3 Task 4): the pick order behind a PICK:{id} work
 * item, with the same picks table + inline confirm ported verbatim from the
 * old pick-orders drawer (pick-order-detail-drawer.tsx, retired in Task 5).
 * Domain data lives in fulfillment, not tasks, so the fetch is separately
 * gated on fulfillment-read: a VIEWER holding only task-read can see the work
 * item in the inbox but would 403 on GET /pick-orders/{id}, so this pane
 * shows an honest access message instead of firing that request.
 */
export function PickPane({ workItem }: PickPaneProps) {
  const { hasPermission } = usePermissions();
  const canRead = hasPermission('fulfillment-read');
  const canConfirm = hasPermission('fulfillment-write');

  const pickOrderId = canRead ? refSourceId(workItem.ref) : undefined;
  const { data: po, isLoading } = usePickOrder(pickOrderId);
  const { data: deliveryOrder } = useDeliveryOrder(po?.deliveryOrderId ?? undefined);
  const confirm = useConfirmPick();
  const cancelOrder = useCancelPickOrder();
  const bulkLines = useBulkLines(po?.id, !!po?.bulk);
  const bulkConfirm = useBulkConfirm();
  const [confirmingId, setConfirmingId] = useState<number | undefined>();
  const [confirmingBulkId, setConfirmingBulkId] = useState<number | undefined>();
  const [confirmCancel, setConfirmCancel] = useState(false);
  // Row :1470 (A8): dismissed locally per pane instance -- the notice is a derived, self-healing
  // signal (see PickOrderResponse.autoOpenPending's backend KDoc), so there is nothing to
  // persist here; a later reload simply re-derives it from the current state.
  //
  // Review fix: WorkDetailPane renders PickPane unkeyed across different work items, so this
  // state would otherwise leak across pick orders (dismissing order A's notice would also hide
  // order B's) and would never re-show a notice that goes true -> false -> true again on the
  // SAME mounted pane (e.g. a manual open failed, then failed again after a retry). Resetting
  // on [po?.id, po?.autoOpenPending] fixes both: a new order always starts undismissed, and a
  // false->true re-arm (dependency changes even though the visual state was already hidden)
  // clears any stale dismissal before the notice would need to show again.
  const [autoOpenNoticeDismissed, setAutoOpenNoticeDismissed] = useState(false);
  useEffect(() => {
    setAutoOpenNoticeDismissed(false);
  }, [po?.id, po?.autoOpenPending]);

  if (!canRead) {
    return (
      <div
        className="flex h-full min-h-[300px] flex-col items-center justify-center gap-3 rounded-2xl border border-border bg-card text-center text-muted-foreground"
        data-testid="pick-pane-no-access"
      >
        <p className="text-sm">You need fulfillment access to view pick details.</p>
      </div>
    );
  }

  if (isLoading || !po) {
    return (
      <div className="space-y-4 rounded-2xl border border-border bg-card p-6">
        <Skeleton className="h-6 w-1/2" />
        <Skeleton className="h-40 w-full" />
      </div>
    );
  }

  // Backend gate: cancel is refused (409) at/past PICKED(600) — mirror it so
  // the button never 4xxs (same pattern as order-detail.tsx's isCancelable).
  const isCancelable = canConfirm && po.state < PICKED;

  return (
    <div className="space-y-4">
      <SectionCard>
        <div className="flex items-center gap-3">
          <h1 className="font-display numeric text-[20px] font-bold text-foreground">
            {po.pickOrderNumber}
          </h1>
          <StatusPill label={pickStateName(po.state)} tone={pickPillTone(po.state)} />
          {/* Read-only document — no write-perm gate; useful in every
              pre-terminal pick-order state (mirrors the backend's own lack
              of an availability gate on this route). */}
          <Button
            variant="outline"
            size="sm"
            className="ml-auto"
            data-testid="doc-pick-ticket-btn"
            onClick={() => viewPdf(`/api/v1/pick-orders/${po.id}/pick-ticket.pdf`)}
          >
            Pick ticket
          </Button>
          <Button
            variant="outline"
            size="sm"
            data-testid="doc-pick-ticket-archive-btn"
            onClick={() => archiveDocument(`/api/v1/pick-orders/${po.id}/pick-ticket.pdf`)}
          >
            Archive
          </Button>
          {isCancelable && (
            <Button
              variant="outline"
              size="sm"
              className="text-destructive"
              onClick={() => setConfirmCancel(true)}
              disabled={cancelOrder.isPending}
              data-testid="pick-order-cancel-btn"
            >
              Cancel
            </Button>
          )}
        </div>
        <p className="mt-1 text-[13px] text-foreground/70">
          Order {po.deliveryOrderNumber ?? '—'}
          <span className="mx-2 text-foreground/30">·</span>
          <span data-testid="pick-order-weight">
            {po.weight != null ? `${po.weight} kg` : '—'}
          </span>
          <span className="mx-1 text-foreground/30">/</span>
          <span data-testid="pick-order-volume">
            {po.volume != null ? `${po.volume} m³` : '—'}
          </span>
        </p>

        {deliveryOrder?.pickingHint && (
          <div
            className="mt-4 rounded-md border border-signal/25 bg-signal/10 p-3"
            data-testid="pick-pane-hint"
          >
            <p className="text-[13px] font-medium text-foreground">{deliveryOrder.pickingHint}</p>
          </div>
        )}

        {/* Row :1470 (A8): derived, dismissible -- see PickOrderResponse.autoOpenPending's
            backend KDoc. Re-appears on the next load if still true; dismissal is local-only
            and not persisted (there is nothing to persist -- the signal self-heals). */}
        {po.autoOpenPending && !autoOpenNoticeDismissed && (
          <div
            className="mt-4 flex items-start justify-between gap-3 rounded-md border border-warning bg-warning/15 p-3 text-sm font-medium text-warning-foreground"
            data-testid="pick-pane-auto-open-pending"
          >
            <span>
              Automatic shipment opening did not complete; open packing manually or re-check the
              strategy.
            </span>
            <button
              type="button"
              onClick={() => setAutoOpenNoticeDismissed(true)}
              className="flex-none text-[12px] font-medium text-warning-foreground/80 hover:text-warning-foreground"
              data-testid="pick-pane-auto-open-pending-dismiss"
            >
              Dismiss
            </button>
          </div>
        )}

        {/* Bulk orders route confirms through the bulk-lines pane below; this table stays
            visible for a bulk order too, as a read-only per-pick record (no Confirm column,
            no confirm form). */}
        <div className="mt-5">
            <Table data-testid="picks-table">
              <TableHeader>
                <TableRow>
                  <TableHead>Item</TableHead>
                  <TableHead>Source</TableHead>
                  <TableHead>Lot</TableHead>
                  <TableHead className="text-right">Planned</TableHead>
                  <TableHead className="text-right">Picked</TableHead>
                  <TableHead>Type</TableHead>
                  <TableHead>State</TableHead>
                  <TableHead />
                </TableRow>
              </TableHeader>
              <TableBody>
                {po.picks.map((p) => (
                  <Fragment key={p.id}>
                    <TableRow data-testid={`pick-row-${p.id}`}>
                      <TableCell className="font-mono text-[13px]">
                        {p.itemDataNumber}
                        {p.substitutedItemDataId !== null && (
                          <Badge variant="outline" className="ml-1">
                            sub
                          </Badge>
                        )}
                        {p.followUpForPickId !== null && (
                          <Badge variant="outline" className="ml-1">
                            follow-up
                          </Badge>
                        )}
                        {p.state === 600 && p.pickedAmount < p.plannedAmount && (
                          <Badge variant="warning" className="ml-1">
                            short
                          </Badge>
                        )}
                      </TableCell>
                      <TableCell className="text-[13px]">Stock {p.sourceStockUnitId}</TableCell>
                      <TableCell className="text-[13px]" data-testid={`pick-lot-${p.id}`}>
                        {/* Least-cluttered honest layout: one column, current
                            truth — the ACTUAL picked lot once confirmed
                            (pickedLotNumber, row 18), falling back to the
                            PLANNED lot pre-confirm (muted, since it's not yet
                            fact), "—" when neither exists. */}
                        {p.pickedLotNumber ? (
                          <span className="numeric">{p.pickedLotNumber}</span>
                        ) : p.lotNumber ? (
                          <span className="numeric text-muted-foreground/70">{p.lotNumber}</span>
                        ) : (
                          '—'
                        )}
                        {p.pickedBestBefore && (
                          <span className="ml-1 numeric text-[11px] text-muted-foreground">
                            {p.pickedBestBefore}
                          </span>
                        )}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">{p.plannedAmount}</TableCell>
                      <TableCell className="text-right tabular-nums">{p.pickedAmount}</TableCell>
                      <TableCell>
                        <Badge variant="secondary">{p.pickingType}</Badge>
                      </TableCell>
                      <TableCell>
                        <Badge variant={pickStateVariant(p.state)}>{pickStateName(p.state)}</Badge>
                      </TableCell>
                      <TableCell className="text-right">
                        {!po.bulk && canConfirm && p.state === 100 && confirmingId !== p.id && (
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => setConfirmingId(p.id)}
                            data-testid={`pick-confirm-btn-${p.id}`}
                          >
                            Confirm
                          </Button>
                        )}
                      </TableCell>
                    </TableRow>
                    {!po.bulk && confirmingId === p.id && (
                      <TableRow>
                        <TableCell colSpan={8}>
                          <PickConfirmForm
                            plannedAmount={p.plannedAmount}
                            isPending={confirm.isPending}
                            onCancel={() => setConfirmingId(undefined)}
                            onConfirm={(pickedAmount, targetUnitLoadId) =>
                              confirm.mutate(
                                { pickId: p.id, body: { pickedAmount, targetUnitLoadId } },
                                { onSuccess: () => setConfirmingId(undefined) },
                              )
                            }
                          />
                        </TableCell>
                      </TableRow>
                    )}
                  </Fragment>
                ))}
              </TableBody>
            </Table>
          </div>
      </SectionCard>

      {po.bulk && (
        <SectionCard title="Bulk pick lines" noPad>
          <Table data-testid="bulk-lines-table">
            <TableHeader>
              <TableRow>
                <TableHead>Location</TableHead>
                <TableHead>Unit load</TableHead>
                <TableHead>SKU</TableHead>
                <TableHead>Lot</TableHead>
                <TableHead className="text-right">Qty</TableHead>
                <TableHead className="text-right">Orders</TableHead>
                {canConfirm && <TableHead />}
              </TableRow>
            </TableHeader>
            <TableBody>
              {bulkLines.isLoading && (
                <TableRow>
                  <TableCell colSpan={7}>Loading bulk lines...</TableCell>
                </TableRow>
              )}
              {bulkLines.isError && (
                <TableRow>
                  <TableCell colSpan={7}>
                    {bulkLines.error instanceof Error
                      ? bulkLines.error.message
                      : 'Failed to load bulk lines.'}
                  </TableCell>
                </TableRow>
              )}
              {!bulkLines.isLoading && !bulkLines.isError && (bulkLines.data ?? []).map((l) => (
                <TableRow key={l.sourceStockUnitId} data-testid={`bulk-line-${l.sourceStockUnitId}`}>
                  <TableCell className="font-mono">{l.locationName}</TableCell>
                  <TableCell className="font-mono">{l.unitLoadLabel}</TableCell>
                  <TableCell>{l.itemDataNumber}</TableCell>
                  <TableCell>{l.lotNumber ?? '-'}</TableCell>
                  <TableCell className="numeric text-right">{l.plannedTotal}</TableCell>
                  <TableCell className="numeric text-right">{l.openSlices} orders</TableCell>
                  {canConfirm && (
                    <TableCell className="text-right">
                      {confirmingBulkId !== l.sourceStockUnitId ? (
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => setConfirmingBulkId(l.sourceStockUnitId)}
                        >
                          Confirm
                        </Button>
                      ) : (
                        <PickConfirmForm
                          plannedAmount={l.plannedTotal}
                          isPending={bulkConfirm.isPending}
                          onCancel={() => setConfirmingBulkId(undefined)}
                          onConfirm={(pickedAmount, targetUnitLoadId) =>
                            bulkConfirm.mutate(
                              {
                                pickOrderId: po.id,
                                body: { sourceStockUnitId: l.sourceStockUnitId, pickedAmount, targetUnitLoadId },
                              },
                              {
                                onSuccess: (r) => {
                                  setConfirmingBulkId(undefined);
                                  toast.success(`${r.filledSlices} orders filled, ${r.shortSlices} short`);
                                },
                              },
                            )
                          }
                        />
                      )}
                    </TableCell>
                  )}
                </TableRow>
              ))}
              {!bulkLines.isLoading && !bulkLines.isError && bulkLines.data?.length === 0 && (
                <TableRow>
                  <TableCell colSpan={7}>All bulk lines picked.</TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </SectionCard>
      )}

      <AlertDialog open={confirmCancel} onOpenChange={setConfirmCancel}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Cancel pick order?</AlertDialogTitle>
            <AlertDialogDescription>
              Open picks are canceled and reserved stock is released.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Keep pick order</AlertDialogCancel>
            <AlertDialogAction
              onClick={() =>
                cancelOrder.mutate(po.id, { onSuccess: () => setConfirmCancel(false) })
              }
              data-testid="pick-order-cancel-confirm-btn"
            >
              Cancel pick order
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
