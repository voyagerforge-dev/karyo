import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import { ArrowUpRight, Ban, Pause, Pencil, Play, Undo2 } from 'lucide-react';
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
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { SectionCard } from '@/components/control/section-card';
import { AttributeGrid } from '@/components/control/attribute-grid';
import { StatusPill } from '@/components/control/status-pill';
import { usePermissions } from '@/hooks/use-permissions';
import { useAuth } from '@/components/auth/auth-provider';
import { LocationPicker, type PickedLocation } from './location-picker';
import {
  useGoodsReceipt,
  useClaimReceipt,
  useReleaseReceipt,
  usePauseReceipt,
  useResumeReceipt,
  useUpdateGoodsReceipt,
  useCancelReceipt,
  useReverseLine,
} from './use-receiving';
import { getReceiptStatus, RECEIPT_LOCK_LABELS } from './receiving-status';
import { GOODS_RECEIPT_TYPE } from '@/types/receiving';

interface ReceiptDetailProps {
  receiptId: number;
  /** Whether the user holds order-write */
  canWrite: boolean;
}

function fmtDateTime(iso: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString();
}

/**
 * Receipt detail-as-workspace (Task 3 of the receiving Control migration).
 * Header + B7 attribute grid, claim/release/pause/resume controls, header
 * edit dialog and the per-line summary table. Mirrors AsnDetail (Task 2).
 * The full receiving workbench (scanning lines in) stays a separate page at
 * /receiving/{id} -- this pane's "Open workbench" button jumps there.
 */
export function ReceiptDetail({ receiptId, canWrite }: ReceiptDetailProps) {
  const navigate = useNavigate();
  const { hasPermission } = usePermissions();
  const { userName } = useAuth();
  const isManager = hasPermission('MANAGER');

  const { data: receipt, isLoading } = useGoodsReceipt(receiptId);
  const claimMutation = useClaimReceipt();
  const releaseMutation = useReleaseReceipt();
  const pauseMutation = usePauseReceipt();
  const resumeMutation = useResumeReceipt();
  const updateMutation = useUpdateGoodsReceipt();
  const cancelMutation = useCancelReceipt();
  const reverseLineMutation = useReverseLine();

  const [confirmCancel, setConfirmCancel] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const [editPrio, setEditPrio] = useState('');
  const [editDate, setEditDate] = useState('');
  const [editDock, setEditDock] = useState<PickedLocation | null>(null);
  const [clearDock, setClearDock] = useState(false);
  const [reverseLineId, setReverseLineId] = useState<number | null>(null);

  // Reset the edit draft whenever a different receipt is selected or opened.
  useEffect(() => {
    if (!receipt) return;
    setEditPrio(String(receipt.prio));
    setEditDate(receipt.receiptDate ?? '');
    setEditDock(
      receipt.dockLocationId != null
        ? { id: receipt.dockLocationId, name: receipt.dockLocationName ?? '' }
        : null,
    );
    setClearDock(false);
  }, [receipt]);

  if (isLoading || !receipt) {
    return (
      <div className="space-y-4 rounded-2xl border border-border bg-card p-6">
        <Skeleton className="h-6 w-1/2" />
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-40 w-full" />
      </div>
    );
  }

  const status = getReceiptStatus(receipt);
  const isMutating =
    claimMutation.isPending ||
    releaseMutation.isPending ||
    pauseMutation.isPending ||
    resumeMutation.isPending ||
    updateMutation.isPending ||
    cancelMutation.isPending;

  const isOwner = receipt.operatorId != null && receipt.operatorId === userName;
  const isClaimedByOther = receipt.operatorId != null && !isOwner;
  // Claim is NOT idempotent (self-reclaim 409s) so it's only ever offered
  // when unclaimed, and pause blocks receiving but not claim/release, so
  // claim stays keyed off state alone. Release is deliberately NOT gated on
  // `status.open` -- the backend allows releasing a claim in any state.
  const canClaim = status.open && receipt.operatorId == null;
  const canPause = status.open && !receipt.pausedAt;
  // Backend cancels EMPTY receipts only (zero lines; 409 otherwise -- see
  // useCancelReceipt docstring). Every STARTED(500) receipt structurally has
  // >=1 line, so gating on `status.open` alone would offer a Cancel button
  // that always 409s on a STARTED receipt. A receipt with lines can only be
  // finished, never canceled. Every CANCELED receipt also has zero lines
  // (that's how it got there), so the line-count check alone isn't enough --
  // `status.open` is required too, or an already-canceled receipt shows a
  // live Cancel button that 409s (state is already closed).
  const isCancelable = receipt.lines.length === 0 && status.open;

  // Mirrors the backend gate (GoodsReceiptService.requireReversibleReceipt):
  // CREATED/STARTED only, and not paused. `status.open` alone isn't enough --
  // it stays true while paused (getReceiptStatus keeps `open` unchanged for
  // the "Paused" label), so pausedAt needs its own check here too.
  const canReverseLines = canWrite && status.open && !receipt.pausedAt;

  function handleEditSave() {
    updateMutation.mutate(
      {
        id: receipt!.id,
        prio: editPrio.trim() === '' ? undefined : Number(editPrio),
        receiptDate: editDate || undefined,
        // NOTE: sending dockLocationId/Name as undefined to "clear" the dock
        // is a known server-side no-op -- PUT only applies fields it
        // receives, it can't null one out (filed in WORKLIST as a defect).
        // The checkbox is kept so operator intent is visible even though the
        // backend currently ignores it.
        dockLocationId: clearDock ? undefined : editDock?.id,
        dockLocationName: clearDock ? undefined : editDock?.name,
      },
      { onSuccess: () => setEditOpen(false) },
    );
  }

  function handleConfirmCancel() {
    cancelMutation.mutate(receipt!.id, { onSuccess: () => setConfirmCancel(false) });
  }

  function handleConfirmReverseLine() {
    if (reverseLineId == null) return;
    reverseLineMutation.mutate(
      { id: receipt!.id, lineId: reverseLineId },
      { onSuccess: () => setReverseLineId(null) },
    );
  }

  return (
    <div className="space-y-4">
      <SectionCard>
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0">
            <div className="flex items-center gap-3">
              <h1 className="font-display numeric text-[20px] font-bold text-foreground">
                {receipt.receiptNumber}
              </h1>
              <span data-testid="receipt-detail-state">
                <StatusPill label={status.label} tone={status.tone} />
              </span>
              {receipt.receiptType === GOODS_RECEIPT_TYPE.RETOUR && (
                <StatusPill label="RETOUR" tone="amber" />
              )}
            </div>
            <p className="mt-1 text-[13px] text-foreground/70">
              {receipt.carrierName ?? 'No carrier'}
            </p>
          </div>
          {/* Kept enabled while paused -- the workbench itself communicates
              the pause and blocks receiving; browsing there is always fine. */}
          <Button
            onClick={() => navigate(`/receiving/${receipt.id}`)}
            data-testid="receipt-open-workbench"
          >
            <ArrowUpRight className="size-4" />
            Open workbench
          </Button>
        </div>

        <div className="mt-5">
          <AttributeGrid
            items={[
              {
                label: receipt.asns.length === 1 ? 'ASN' : 'ASNs',
                value:
                  receipt.asns.length === 0
                    ? null
                    : receipt.asns.map((a) => a.asnNumber).join(', '),
              },
              { label: 'Carrier', value: receipt.carrierName },
              { label: 'Delivery note', value: receipt.deliveryNoteNumber },
              {
                label: 'Type',
                value: receipt.receiptType === GOODS_RECEIPT_TYPE.RETOUR ? 'RETOUR' : 'NORMAL',
              },
              { label: 'Prio', value: String(receipt.prio) },
              {
                label: 'Receipt date',
                value: receipt.receiptDate
                  ? new Date(receipt.receiptDate).toLocaleDateString()
                  : null,
              },
              { label: 'Dock', value: receipt.dockLocationName },
              { label: 'Operator (claim)', value: receipt.operatorId },
              {
                label: 'Paused since',
                value: receipt.pausedAt ? fmtDateTime(receipt.pausedAt) : null,
              },
              { label: 'Created', value: new Date(receipt.created).toLocaleString() },
            ]}
          />
        </div>

        {/* pausedAt isn't cleared by cancel/finish (see GoodsReceiptService),
            so a closed receipt can still carry a stale pause stamp -- gate on
            `status.open` or a canceled-while-paused receipt would advertise a
            "Paused"/Resume state it can no longer act on. */}
        {status.open && receipt.pausedAt && (
          <div
            className="mt-4 rounded-md border border-warning bg-warning/15 p-3 text-sm font-medium text-warning-foreground"
            data-testid="receipt-paused-banner"
          >
            Paused since {fmtDateTime(receipt.pausedAt)}
          </div>
        )}

        {canWrite && (
          <div className="mt-5 flex flex-wrap items-center gap-2 border-t border-border pt-4">
            {canClaim && (
              <Button
                onClick={() => claimMutation.mutate(receipt.id)}
                disabled={isMutating}
                data-testid="receipt-claim-button"
              >
                Claim
              </Button>
            )}
            {isOwner && (
              <Button
                variant="outline"
                onClick={() => releaseMutation.mutate(receipt.id)}
                disabled={isMutating}
                data-testid="receipt-release-button"
              >
                Release claim
              </Button>
            )}
            {isClaimedByOther && (
              <>
                <span className="text-[13px] text-muted-foreground">
                  Claimed by {receipt.operatorId}
                </span>
                {isManager && (
                  <Button
                    variant="outline"
                    onClick={() => releaseMutation.mutate(receipt.id)}
                    disabled={isMutating}
                    data-testid="receipt-release-button"
                  >
                    Release (manager)
                  </Button>
                )}
              </>
            )}
            {canPause && (
              <Button
                variant="outline"
                onClick={() => pauseMutation.mutate(receipt.id)}
                disabled={isMutating}
                data-testid="receipt-pause-button"
              >
                <Pause className="size-4" />
                Pause
              </Button>
            )}
            {status.open && receipt.pausedAt && (
              <Button
                onClick={() => resumeMutation.mutate(receipt.id)}
                disabled={isMutating}
                data-testid="receipt-resume-button"
              >
                <Play className="size-4" />
                Resume
              </Button>
            )}
            {/* Backend PUT 409s 'order-not-editable' unless CREATED/STARTED --
                gate on `status.open` so a closed receipt never offers an Edit
                that's guaranteed to fail. */}
            {status.open && (
              <Button
                variant="outline"
                onClick={() => setEditOpen(true)}
                disabled={isMutating}
                data-testid="receipt-edit-button"
              >
                <Pencil className="size-4" />
                Edit
              </Button>
            )}
            {isCancelable && (
              <Button
                variant="outline"
                className="text-destructive"
                onClick={() => setConfirmCancel(true)}
                disabled={isMutating}
                data-testid="receipt-cancel-button"
              >
                <Ban className="size-4" />
                Cancel
              </Button>
            )}
          </div>
        )}
      </SectionCard>

      {/* Line summary */}
      <SectionCard title={`Lines (${receipt.lines.length})`} noPad>
        <Table data-testid="receipt-lines-table">
          <TableHeader>
            <TableRow>
              <TableHead>Product</TableHead>
              <TableHead className="text-right">Amount</TableHead>
              <TableHead>Lock</TableHead>
              <TableHead className="text-right">Status</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {receipt.lines.map((line) => (
              <TableRow key={line.id} className={line.reversed ? 'opacity-50' : undefined}>
                <TableCell>
                  <span className="font-mono text-[13px]">{line.itemDataNumber}</span>
                </TableCell>
                <TableCell className="text-right">
                  <span className="numeric">{line.amount}</span>
                </TableCell>
                <TableCell>
                  {line.lockType != null ? (
                    <StatusPill
                      label={RECEIPT_LOCK_LABELS[line.lockType] ?? `LOCK ${line.lockType}`}
                      tone="amber"
                    />
                  ) : (
                    <span className="text-muted-foreground">—</span>
                  )}
                </TableCell>
                <TableCell className="text-right">
                  {line.reversed ? (
                    <span data-testid={`line-reversed-${line.id}`}>
                      <StatusPill label="Reversed" tone="grey" />
                    </span>
                  ) : (
                    canReverseLines && (
                      <Button
                        variant="outline"
                        size="icon"
                        className="size-7 text-destructive"
                        aria-label="Reverse line"
                        disabled={reverseLineMutation.isPending}
                        onClick={() => setReverseLineId(line.id)}
                        data-testid={`line-reverse-btn-${line.id}`}
                      >
                        <Undo2 className="size-3.5" />
                      </Button>
                    )
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </SectionCard>

      {/* Header edit dialog */}
      <Dialog open={editOpen} onOpenChange={setEditOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Edit receipt header</DialogTitle>
            <DialogDescription>
              Prio, receipt date and dock are partial-update fields — omitted fields keep their
              current value.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div className="space-y-2">
                <Label htmlFor="receipt-edit-prio-input">Prio</Label>
                <Input
                  id="receipt-edit-prio-input"
                  type="number"
                  value={editPrio}
                  onChange={(e) => setEditPrio(e.target.value)}
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="receipt-edit-date-input">Receipt date</Label>
                <Input
                  id="receipt-edit-date-input"
                  type="date"
                  value={editDate}
                  onChange={(e) => setEditDate(e.target.value)}
                />
              </div>
            </div>
            <div className="space-y-2">
              <Label htmlFor="receipt-edit-dock">Dock</Label>
              <LocationPicker
                inputId="receipt-edit-dock"
                value={clearDock ? null : editDock}
                onChange={(loc) => {
                  // Picking a new dock while "Clear dock" is checked should
                  // win over the checkbox -- otherwise handleEditSave still
                  // sends dockLocationId: undefined and the pick is silently
                  // dropped.
                  setEditDock(loc);
                  setClearDock(false);
                }}
              />
              <div className="flex items-center gap-2 pt-1">
                <Checkbox
                  id="receipt-edit-clear-dock"
                  checked={clearDock}
                  onCheckedChange={(v) => setClearDock(v === true)}
                />
                <Label
                  htmlFor="receipt-edit-clear-dock"
                  className="text-xs font-normal text-muted-foreground"
                >
                  Clear dock
                </Label>
              </div>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setEditOpen(false)}>
              Cancel
            </Button>
            <Button
              onClick={handleEditSave}
              disabled={updateMutation.isPending}
              data-testid="receipt-edit-save"
            >
              {updateMutation.isPending ? 'Saving...' : 'Save'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Cancel confirmation */}
      <AlertDialog open={confirmCancel} onOpenChange={setConfirmCancel}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Cancel receipt?</AlertDialogTitle>
            <AlertDialogDescription>
              {`This will cancel "${receipt.receiptNumber}". This cannot be undone.`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Keep receipt</AlertDialogCancel>
            <AlertDialogAction onClick={handleConfirmCancel}>Cancel receipt</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Reverse-line confirmation (B3) */}
      <AlertDialog
        open={reverseLineId != null}
        onOpenChange={(open) => !open && setReverseLineId(null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Reverse line?</AlertDialogTitle>
            <AlertDialogDescription>
              This deletes the received stock and cancels its putaway. This cannot be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Keep line</AlertDialogCancel>
            <AlertDialogAction
              onClick={handleConfirmReverseLine}
              disabled={reverseLineMutation.isPending}
            >
              Reverse line
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
