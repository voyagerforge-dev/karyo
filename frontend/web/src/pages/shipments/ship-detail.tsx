import { useState } from 'react';
import { Ban, Pause, Play, Trash2 } from 'lucide-react';
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
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { SectionCard } from '@/components/control/section-card';
import { StatusPill } from '@/components/control/status-pill';
import { useAuth } from '@/components/auth/auth-provider';
import { usePermissions } from '@/hooks/use-permissions';
import { useDeliveryOrder } from '@/pages/orders/use-orders';
import { getShipmentStatus } from '@/pages/packing/packing-status';
import type { Shipment } from '@/types/shipments';
import { ManifestForm } from './manifest-form';
import { viewPdf, saveZpl, archiveDocument } from '@/lib/document-actions';
import {
  useManifest,
  useDispatch,
  useShipment,
  useClaimShipment,
  useReleaseShipment,
  usePauseShipment,
  useResumeShipment,
  useCancelShipment,
  useRemoveShippingUnit,
  useAddAdHocUnit,
} from './use-shipping';

interface ShipDetailProps {
  shipmentId: number;
  canWrite: boolean;
}

function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString();
}

/** S4: origin -> what removing a shipping unit restores its stock to, for the remove-unit
 * confirm caption. AD_HOC units came straight from ON_STOCK; PACKOUT units came from a pick. */
function restoreCaption(origin: string): string {
  return origin === 'AD_HOC' ? 'back to stock' : 'back to picked';
}

/**
 * Documents block (BOL + packing-slip + shipment-level packet-list PDFs, one ZPL label
 * PER shipping unit and one per-unit content-list PDF, both a `.map` not `[0]` --
 * N-carton-ready). A plain render helper, not a nested component, so it doesn't
 * recreate a component type during render.
 */
function renderDocuments(s: Shipment) {
  return (
    <div className="flex flex-wrap gap-2" data-testid="documents">
      <Button
        variant="outline"
        size="sm"
        data-testid="doc-bol"
        onClick={() => viewPdf(`/api/v1/shipments/${s.id}/bol.pdf`)}
      >
        BOL (PDF)
      </Button>
      <Button
        variant="outline"
        size="sm"
        data-testid="doc-bol-archive"
        onClick={() => archiveDocument(`/api/v1/shipments/${s.id}/bol.pdf`)}
      >
        Archive BOL
      </Button>
      <Button
        variant="outline"
        size="sm"
        data-testid="doc-slip"
        onClick={() => viewPdf(`/api/v1/shipments/${s.id}/packing-slip.pdf`)}
      >
        Packing slip (PDF)
      </Button>
      <Button
        variant="outline"
        size="sm"
        data-testid="doc-slip-archive"
        onClick={() => archiveDocument(`/api/v1/shipments/${s.id}/packing-slip.pdf`)}
      >
        Archive packing slip
      </Button>
      <Button
        variant="outline"
        size="sm"
        data-testid="doc-packet-list-btn"
        onClick={() => viewPdf(`/api/v1/shipments/${s.id}/packet-list.pdf`)}
      >
        Packet list (PDF)
      </Button>
      <Button
        variant="outline"
        size="sm"
        data-testid="doc-packet-list-archive-btn"
        onClick={() => archiveDocument(`/api/v1/shipments/${s.id}/packet-list.pdf`)}
      >
        Archive packet list
      </Button>
      {s.shippingUnits.map((u) => (
        <Button
          key={u.id}
          variant="outline"
          size="sm"
          data-testid={`doc-label-${u.id}`}
          onClick={() => saveZpl(`/api/v1/shipping-units/${u.id}/label.zpl`, `${u.shippingUnitNumber}.zpl`)}
        >
          Label {u.shippingUnitNumber} (ZPL)
        </Button>
      ))}
      {s.shippingUnits.map((u) => (
        <Button
          key={`label-archive-${u.id}`}
          variant="outline"
          size="sm"
          data-testid={`doc-label-archive-${u.id}`}
          onClick={() => archiveDocument(`/api/v1/shipping-units/${u.id}/label.zpl`)}
        >
          Archive label {u.shippingUnitNumber}
        </Button>
      ))}
      {s.shippingUnits.map((u) => (
        <Button
          key={`content-${u.id}`}
          variant="outline"
          size="sm"
          data-testid={`doc-content-list-btn-${u.id}`}
          onClick={() => viewPdf(`/api/v1/shipping-units/${u.id}/content-list.pdf`)}
        >
          Contents {u.shippingUnitNumber} (PDF)
        </Button>
      ))}
      {s.shippingUnits.map((u) => (
        <Button
          key={`content-archive-${u.id}`}
          variant="outline"
          size="sm"
          data-testid={`doc-content-list-archive-btn-${u.id}`}
          onClick={() => archiveDocument(`/api/v1/shipping-units/${u.id}/content-list.pdf`)}
        >
          Archive contents {u.shippingUnitNumber}
        </Button>
      ))}
    </div>
  );
}

/**
 * Shipment detail pane (P4 Task 2) -- ports ship-drawer.tsx's behavior onto the Control
 * master-detail kit. Body branches on `active.state` (NOT a local flag); `active` prefers
 * the freshest returned shipment across every lifecycle mutation (Task 9 extends the
 * dispatch/manifest precedent to claim/release/pause/resume/cancel/removeUnit/addAdHoc) --
 * newest `submittedAt` wins, an id guard then stops a stale result from a previous
 * selection leaking into a newly-selected one, falling back to the fresh per-id fetch
 * instead. The mutations stay mounted across selections (page-level hooks would be
 * re-created per pane instance here since this pane itself owns them).
 */
export function ShipDetail({ shipmentId, canWrite }: ShipDetailProps) {
  const manifest = useManifest();
  const dispatch = useDispatch();
  const fresh = useShipment(shipmentId);
  const claimMutation = useClaimShipment();
  const releaseMutation = useReleaseShipment();
  const pauseMutation = usePauseShipment();
  const resumeMutation = useResumeShipment();
  const cancelMutation = useCancelShipment();
  const removeUnitMutation = useRemoveShippingUnit();
  const addAdHocMutation = useAddAdHocUnit();

  const { userName } = useAuth();
  const { hasPermission } = usePermissions();
  const isManager = hasPermission('MANAGER');

  const [confirmCancel, setConfirmCancel] = useState(false);
  const [removeUnitId, setRemoveUnitId] = useState<number | null>(null);
  const [adHocInput, setAdHocInput] = useState('');

  const mutationResults: Array<{ data?: Shipment; submittedAt?: number }> = [
    claimMutation,
    releaseMutation,
    pauseMutation,
    resumeMutation,
    cancelMutation,
    removeUnitMutation,
    addAdHocMutation,
    dispatch,
    manifest,
  ];
  const latest = mutationResults
    .filter((m) => m.data != null)
    .sort((a, b) => (b.submittedAt ?? 0) - (a.submittedAt ?? 0))[0]?.data;
  const active = latest && latest.id === shipmentId ? latest : fresh.data;

  const { data: deliveryOrder } = useDeliveryOrder(active?.deliveryOrderId ?? undefined);
  const status = active ? getShipmentStatus(active) : undefined;

  const isMutating =
    manifest.isPending ||
    dispatch.isPending ||
    claimMutation.isPending ||
    releaseMutation.isPending ||
    pauseMutation.isPending ||
    resumeMutation.isPending ||
    cancelMutation.isPending ||
    removeUnitMutation.isPending ||
    addAdHocMutation.isPending;

  const isOwner = active?.operatorId != null && active.operatorId === userName;
  const isClaimedByOther = active?.operatorId != null && !isOwner;
  // Backend closes claim at state >= SHIPPED(680) AND refuses while paused (S3
  // ShippingLifecycleService.requireClaimable) -- both need to gate the button, or the
  // Claim click would round-trip into a guaranteed 409.
  const canClaim = active != null && active.state < 680 && active.operatorId == null && !active.pausedAt;
  const canPause = active != null && active.state < 680 && !active.pausedAt;
  // Cancel and per-unit remove share the backend's CANCELED window: PACKING/PACKED only
  // (ShipmentState.canAdvanceTo gates CANCELED to code < SHIPPING). Ad-hoc attach shares the
  // same PACKING/PACKED window (AdHocShippingUnitService.requireOpenForAdHoc) but ALSO
  // refuses while paused (requireShipmentNotPaused) -- cancel/removeUnit do not.
  const preManifest = active != null && (active.state === 640 || active.state === 650);
  const canAddAdHoc = preManifest && !active?.pausedAt;
  // pausedAt is never cleared by a terminal transition (the receipt-detail precedent:
  // "pausedAt isn't cleared by cancel/finish... gate on `status.open` or a canceled-while-
  // paused receipt would advertise a Paused/Resume state it can no longer act on"). A
  // shipment can be canceled while still paused (dispatch refuses while paused), so the
  // banner and Resume button both need the state<SHIPPED guard too, or a closed shipment
  // would show a live Resume that silently mutates a terminal shipment.
  const isPausedLive = active != null && active.state < 680 && !!active.pausedAt;

  function handleConfirmCancel() {
    if (!active) return;
    cancelMutation.mutate(active.id, { onSuccess: () => setConfirmCancel(false) });
  }

  function handleConfirmRemoveUnit() {
    if (!active || removeUnitId == null) return;
    removeUnitMutation.mutate(
      { shipmentId: active.id, unitId: removeUnitId },
      { onSuccess: () => setRemoveUnitId(null) },
    );
  }

  function handleAttachAdHoc() {
    if (!active) return;
    const unitLoadId = Number(adHocInput);
    if (!adHocInput.trim() || Number.isNaN(unitLoadId)) return;
    addAdHocMutation.mutate(
      { shipmentId: active.id, unitLoadId },
      { onSuccess: () => setAdHocInput('') },
    );
  }

  const removingUnit = active?.shippingUnits.find((u) => u.id === removeUnitId);

  return (
    <div className="space-y-4" data-testid="ship-detail">
      {!active ? (
        <SectionCard>
          <div className="space-y-3">
            <Skeleton className="h-6 w-1/2" />
            <Skeleton className="h-32 w-full" />
          </div>
        </SectionCard>
      ) : (
        <>
          <SectionCard>
            <div className="flex items-center gap-3">
              <h1 className="font-display numeric text-[20px] font-bold text-foreground">
                {active.deliveryOrderNumber ?? active.orders?.map((o) => o.number).join(', ') ?? '—'}
                <span className="mx-2 text-foreground/40">{'→'}</span>
                {active.shipmentNumber}
              </h1>
              {status && <StatusPill label={status.label} tone={status.tone} />}
            </div>
            <p className="mt-1 text-[13px] text-foreground/70">
              Manifest, dispatch and shipping documents.
            </p>

            {deliveryOrder?.shippingHint && (
              <div
                className="mt-4 rounded-md border border-signal/25 bg-signal/10 p-3"
                data-testid="ship-pane-hint"
              >
                <p className="text-[13px] font-medium text-foreground">
                  {deliveryOrder.shippingHint}
                </p>
              </div>
            )}

            {isPausedLive && (
              <div
                className="mt-4 rounded-md border border-warning bg-warning/15 p-3 text-sm font-medium text-warning-foreground"
                data-testid="ship-paused-banner"
              >
                Paused since {fmtDateTime(active.pausedAt)}
              </div>
            )}

            {canWrite && (
              <div className="mt-5 flex flex-wrap items-center gap-2 border-t border-border pt-4">
                {canClaim && (
                  <Button
                    onClick={() => claimMutation.mutate(active.id)}
                    disabled={isMutating}
                    data-testid="ship-claim-button"
                  >
                    Claim
                  </Button>
                )}
                {isOwner && (
                  <Button
                    variant="outline"
                    onClick={() => releaseMutation.mutate(active.id)}
                    disabled={isMutating}
                    data-testid="ship-release-button"
                  >
                    Release claim
                  </Button>
                )}
                {isClaimedByOther && (
                  <>
                    <span className="text-[13px] text-muted-foreground">
                      Claimed by {active.operatorId}
                    </span>
                    {isManager && (
                      <Button
                        variant="outline"
                        onClick={() => releaseMutation.mutate(active.id)}
                        disabled={isMutating}
                        data-testid="ship-release-button"
                      >
                        Release (manager)
                      </Button>
                    )}
                  </>
                )}
                {canPause && (
                  <Button
                    variant="outline"
                    onClick={() => pauseMutation.mutate(active.id)}
                    disabled={isMutating}
                    data-testid="ship-pause-button"
                  >
                    <Pause className="size-4" />
                    Pause
                  </Button>
                )}
                {isPausedLive && (
                  <Button
                    onClick={() => resumeMutation.mutate(active.id)}
                    disabled={isMutating}
                    data-testid="ship-resume-button"
                  >
                    <Play className="size-4" />
                    Resume
                  </Button>
                )}
                {preManifest && (
                  <Button
                    variant="outline"
                    className="text-destructive"
                    onClick={() => setConfirmCancel(true)}
                    disabled={isMutating}
                    data-testid="ship-cancel-button"
                  >
                    <Ban className="size-4" />
                    Cancel
                  </Button>
                )}
              </div>
            )}
          </SectionCard>

          <SectionCard title={`Shipping units (${active.shippingUnits.length})`} noPad>
            <Table data-testid="ship-units-table">
              <TableHeader>
                <TableRow>
                  <TableHead>Box</TableHead>
                  <TableHead>Unit #</TableHead>
                  <TableHead>Type</TableHead>
                  <TableHead className="text-right">Weight</TableHead>
                  <TableHead>Carrier label</TableHead>
                  <TableHead>Tracking #</TableHead>
                  {canWrite && preManifest && <TableHead className="text-right">Remove</TableHead>}
                </TableRow>
              </TableHeader>
              <TableBody>
                {active.shippingUnits.map((u) => (
                  <TableRow key={u.id} data-testid={`ship-unit-row-${u.id}`}>
                    <TableCell className="numeric">Box {u.positionIndex}</TableCell>
                    <TableCell className="font-mono text-[13px]">{u.shippingUnitNumber}</TableCell>
                    <TableCell>{u.type}</TableCell>
                    <TableCell className="text-right tabular-nums">{u.weight}</TableCell>
                    <TableCell>{u.carrierLabel ?? '—'}</TableCell>
                    <TableCell>{u.trackingNumber ?? '—'}</TableCell>
                    {canWrite && preManifest && (
                      <TableCell className="text-right">
                        <Button
                          variant="outline"
                          size="icon"
                          className="size-7 text-destructive"
                          aria-label="Remove unit"
                          disabled={removeUnitMutation.isPending}
                          onClick={() => setRemoveUnitId(u.id)}
                          data-testid={`ship-unit-remove-btn-${u.id}`}
                        >
                          <Trash2 className="size-3.5" />
                        </Button>
                      </TableCell>
                    )}
                  </TableRow>
                ))}
              </TableBody>
            </Table>

            {canWrite && canAddAdHoc && (
              <div className="flex items-end gap-2 border-t border-border p-4">
                <div className="flex-1 space-y-1">
                  <Label
                    htmlFor="ad-hoc-unit-load-input"
                    className="text-xs font-normal text-muted-foreground"
                  >
                    Attach unit load
                  </Label>
                  <Input
                    id="ad-hoc-unit-load-input"
                    type="number"
                    placeholder="Unit load ID"
                    value={adHocInput}
                    onChange={(e) => setAdHocInput(e.target.value)}
                    data-testid="ad-hoc-unit-input"
                  />
                </div>
                <Button
                  onClick={handleAttachAdHoc}
                  disabled={addAdHocMutation.isPending || !adHocInput.trim()}
                  data-testid="ad-hoc-attach-btn"
                >
                  Attach
                </Button>
              </div>
            )}
          </SectionCard>

          {active.state === 650 ? (
            canWrite ? (
              <ManifestForm
                isPending={manifest.isPending}
                onConfirm={(c, s, t) =>
                  manifest.mutate({
                    shipmentId: active.id,
                    body: { carrierName: c, carrierService: s, trackingNumber: t },
                  })
                }
                onCancel={() => {}}
              />
            ) : (
              <SectionCard>
                <p className="text-sm text-muted-foreground">Awaiting manifest.</p>
              </SectionCard>
            )
          ) : active.state === 670 ? (
            <SectionCard title="Dispatch">
              <div className="space-y-4">
                <p className="text-sm tabular-nums">
                  {active.carrierName} · {active.trackingNumber}
                </p>
                <Button
                  data-testid="dispatch-btn"
                  disabled={!canWrite || dispatch.isPending}
                  onClick={() => dispatch.mutate(active.id)}
                >
                  {dispatch.isPending ? 'Dispatching…' : 'Dispatch'}
                </Button>
                {renderDocuments(active)}
              </div>
            </SectionCard>
          ) : active.state === 680 ? (
            <SectionCard title="Shipped">
              <div className="space-y-4">
                <p className="text-sm tabular-nums">
                  Shipped · {active.carrierName} · {active.trackingNumber}
                </p>
                {renderDocuments(active)}
              </div>
            </SectionCard>
          ) : active.state === 640 ? (
            <SectionCard>
              <p className="text-sm text-muted-foreground">
                Finish packing on the Packing page.
              </p>
            </SectionCard>
          ) : active.state === 800 ? (
            <SectionCard title="Shipment canceled">
              <p className="text-sm text-muted-foreground">
                Every shipping unit's stock was restored per origin.
              </p>
            </SectionCard>
          ) : null}

          <AlertDialog open={confirmCancel} onOpenChange={setConfirmCancel}>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Cancel shipment?</AlertDialogTitle>
                <AlertDialogDescription>
                  {`This cancels "${active.shipmentNumber}" and restores every shipping unit's stock (back to picked for packed units, back to stock for ad-hoc units). This cannot be undone.`}
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Keep shipment</AlertDialogCancel>
                <AlertDialogAction
                  variant="destructive"
                  onClick={handleConfirmCancel}
                  disabled={cancelMutation.isPending}
                  data-testid="ship-cancel-confirm-btn"
                >
                  Cancel shipment
                </AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>

          <AlertDialog
            open={removeUnitId != null}
            onOpenChange={(open) => !open && setRemoveUnitId(null)}
          >
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Remove shipping unit?</AlertDialogTitle>
                <AlertDialogDescription>
                  {removingUnit
                    ? `This removes ${removingUnit.shippingUnitNumber} from the shipment and restores its stock ${restoreCaption(removingUnit.origin)}. This cannot be undone.`
                    : 'This removes the shipping unit from the shipment. This cannot be undone.'}
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Keep unit</AlertDialogCancel>
                <AlertDialogAction
                  onClick={handleConfirmRemoveUnit}
                  disabled={removeUnitMutation.isPending}
                  data-testid="ship-unit-remove-confirm-btn"
                >
                  Remove unit
                </AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        </>
      )}
    </div>
  );
}
