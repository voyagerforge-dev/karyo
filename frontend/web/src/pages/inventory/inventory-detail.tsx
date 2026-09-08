import { useState } from 'react';
import { Box, Boxes, MoreVertical } from 'lucide-react';
import { toast } from 'sonner';
import { cn } from '@/lib/utils';
import { usePermissions } from '@/hooks/use-permissions';
import { useAllLocations } from '@/pages/locations/use-locations';
import { useClients } from '@/features/clients/use-clients';
import {
  useJournals,
  signedDelta,
  recordTypeMeta,
  type JournalEntry,
} from '@/features/insights/use-journals';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
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
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { StatusPill } from '@/components/control/status-pill';
import { viewPdf, saveZpl, archiveDocument } from '@/lib/document-actions';
import { useExtinguishStock } from '@/features/picking/use-pick-orders';
import type { UnitLoadResponse } from '@/types/inventory';
import {
  moveUnitLoads,
  useAdjustStock,
  useChangeUnitLoadClient,
  useLockUnitLoad,
  useSetStockLock,
  useSetUnitLoadCarrier,
  useTransferToClearing,
  useTransferUnitLoad,
  useUnitLoadsByLocation,
  useUnlockUnitLoad,
} from './use-inventory';
import {
  groupLotLabel,
  type GroupLpn,
  type ItemLocationGroup,
} from './inventory-rows';
import {
  expiryHex,
  toneBgClass,
  toneTextClass,
} from './stock-status';

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** Short month-day label for an ISO date; "—" when null/invalid. */
function fmtIso(iso: string | null): string {
  if (!iso) return '—';
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return '—';
  const d = new Date(t);
  return `${MONTHS[d.getMonth()]} ${String(d.getDate()).padStart(2, '0')}`;
}

function StatCard({
  label,
  value,
  valueClass,
  sub,
  big,
}: {
  label: string;
  value: string;
  valueClass?: string;
  sub?: string;
  big?: boolean;
}) {
  return (
    <div className="rounded-2xl border border-border bg-card p-4">
      <div className="text-label">{label}</div>
      <div
        className={cn(
          'numeric mt-2 font-bold tracking-tight text-foreground',
          big ? 'text-[28px]' : 'text-[18px]',
          valueClass,
        )}
      >
        {value}
      </div>
      {sub && <div className="mt-0.5 text-[11px] text-muted-foreground/70">{sub}</div>}
    </div>
  );
}

function LotField({
  label,
  value,
  valueHex,
  mono = true,
}: {
  label: string;
  value: string;
  valueHex?: string;
  mono?: boolean;
}) {
  return (
    <div>
      <div className="text-label">{label}</div>
      <div
        className={cn('mt-1.5 text-[13.5px] font-semibold text-foreground/90', mono && 'numeric')}
        style={valueHex ? { color: valueHex } : undefined}
      >
        {value}
      </div>
    </div>
  );
}

/**
 * Adjust dialog — sets a stock unit's absolute on-hand amount. When the
 * group spans more than one stock unit (multi-LPN), the operator must pick
 * which constituent unit to adjust rather than guessing an aggregate split.
 */
function AdjustDialog({
  open,
  onOpenChange,
  group,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  group: ItemLocationGroup;
}) {
  const adjustStock = useAdjustStock();
  const single = group.stockUnitIds.length === 1;
  // Initial values only — the caller remounts this dialog (via a `key` tied
  // to open-state) each time it opens, so these never need a reset effect.
  const [selectedId, setSelectedId] = useState<string>(single ? String(group.stockUnitIds[0]) : '');
  const [amount, setAmount] = useState(single ? String(group.onHand) : '');

  const selectedLpn = group.lpns.find((l) => String(l.id) === selectedId);

  const handleSelect = (id: string) => {
    setSelectedId(id);
    const lpn = group.lpns.find((l) => String(l.id) === id);
    if (lpn) setAmount(String(lpn.qty));
  };

  const parsed = Number(amount);
  const canSubmit = selectedId !== '' && amount.trim() !== '' && Number.isFinite(parsed) && parsed >= 0;

  const handleSubmit = async () => {
    if (!canSubmit) return;
    try {
      await adjustStock.mutateAsync({ id: Number(selectedId), newAmount: parsed });
      toast.success('Stock adjusted', { description: `New on-hand: ${parsed.toLocaleString()}` });
      onOpenChange(false);
    } catch {
      // api-client already surfaces a toast for the failed request.
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Adjust stock — {group.name}</DialogTitle>
        </DialogHeader>
        <div className="space-y-4">
          {!single && (
            <div className="space-y-2">
              <Label>Unit to adjust</Label>
              <Select value={selectedId} onValueChange={handleSelect}>
                <SelectTrigger>
                  <SelectValue placeholder="Select an LPN" />
                </SelectTrigger>
                <SelectContent>
                  {group.lpns.map((l) => (
                    <SelectItem key={l.id} value={String(l.id)}>
                      {l.lpn} · {l.qty.toLocaleString()} units
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {group.lpns.length === 0 && (
                <p className="text-[12px] text-muted-foreground">
                  No individually-tracked units in this group to adjust.
                </p>
              )}
            </div>
          )}
          <div className="space-y-2">
            <Label htmlFor="adjust-amount">New on-hand quantity</Label>
            <Input
              id="adjust-amount"
              type="number"
              min={0}
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              placeholder="0"
            />
            {selectedLpn && (
              <p className="text-[12px] text-muted-foreground">
                Currently {selectedLpn.qty.toLocaleString()} on {selectedLpn.lpn}.
              </p>
            )}
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button onClick={handleSubmit} disabled={!canSubmit || adjustStock.isPending}>
            {adjustStock.isPending ? 'Saving…' : 'Save'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Move dialog — relocates every unit load in the group to a chosen
 * destination location. One transfer call per distinct unit load.
 */
function MoveDialog({
  open,
  onOpenChange,
  group,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  group: ItemLocationGroup;
}) {
  const transferUnitLoad = useTransferUnitLoad();
  const { data: locationsData } = useAllLocations();
  const [destinationId, setDestinationId] = useState('');
  const [isMoving, setIsMoving] = useState(false);

  const destinations = (locationsData?.content ?? []).filter((l) => l.id !== group.locationId);
  const canSubmit = destinationId !== '' && group.unitLoadIds.length > 0 && !isMoving;

  const handleSubmit = async () => {
    const destination = destinations.find((l) => String(l.id) === destinationId);
    if (!destination) return;
    setIsMoving(true);
    try {
      await moveUnitLoads(group.unitLoadIds, destination, transferUnitLoad.mutateAsync);
      toast.success(
        `Moved ${group.unitLoadIds.length} unit load${group.unitLoadIds.length === 1 ? '' : 's'}`,
        { description: `To ${destination.name}` },
      );
      onOpenChange(false);
    } catch {
      // api-client already surfaces a toast for the failed request.
    } finally {
      setIsMoving(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Move stock — {group.name}</DialogTitle>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-2">
            <Label>Destination location</Label>
            <Select value={destinationId} onValueChange={setDestinationId}>
              <SelectTrigger>
                <SelectValue placeholder="Select a location" />
              </SelectTrigger>
              <SelectContent>
                {destinations.map((l) => (
                  <SelectItem key={l.id} value={String(l.id)}>
                    {l.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <p className="text-[12px] text-muted-foreground">
              Moves all {group.unitLoadIds.length} unit load
              {group.unitLoadIds.length === 1 ? '' : 's'} at {group.location} to the destination.
            </p>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button onClick={handleSubmit} disabled={!canSubmit}>
            {isMoving ? 'Moving…' : 'Move'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Hold/Release dialog — locks or unlocks every stock unit in the group,
 * recording an optional free-text reason as the journal activityCode.
 */
function HoldDialog({
  open,
  onOpenChange,
  group,
  isHeld,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  group: ItemLocationGroup;
  isHeld: boolean;
}) {
  const setStockLock = useSetStockLock();
  const [reason, setReason] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  const handleSubmit = async () => {
    setIsSubmitting(true);
    try {
      await Promise.all(
        group.stockUnitIds.map((id) =>
          setStockLock.mutateAsync({
            id,
            lockType: isHeld ? 0 : 103,
            reason: reason.trim() || undefined,
          }),
        ),
      );
      toast.success(
        isHeld
          ? `Released ${group.stockUnitIds.length} unit${group.stockUnitIds.length === 1 ? '' : 's'}`
          : `Placed hold on ${group.stockUnitIds.length} unit${group.stockUnitIds.length === 1 ? '' : 's'}`,
      );
      onOpenChange(false);
    } catch {
      // api-client already surfaces a toast for the failed request.
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{isHeld ? 'Release hold' : 'Place hold'} — {group.name}</DialogTitle>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="hold-reason">Reason (optional)</Label>
            <Input
              id="hold-reason"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder={isHeld ? 'e.g. inspection passed' : 'e.g. suspected damage on inbound'}
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button onClick={handleSubmit} disabled={isSubmitting}>
            {isSubmitting ? 'Saving…' : isHeld ? 'Release' : 'Place hold'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Change-owner dialog — reassigns a single unit load's goods-owner (client).
 * The backend additionally requires an OPS principal and total-refuses (409)
 * on reservations/open picks/state >= PICKED; the frontend has no
 * principal-kind claim surface today, so any inventory-write holder sees the
 * action and the global RFC7807 toast explains a refusal in human terms.
 */
function ChangeOwnerDialog({
  open,
  onOpenChange,
  unitLoadId,
  unitLoadLabel,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  unitLoadId: number;
  unitLoadLabel: string;
}) {
  const changeClient = useChangeUnitLoadClient();
  const { data: clients } = useClients();
  const [targetClientId, setTargetClientId] = useState('');
  const [reason, setReason] = useState('');

  // The system client (id 0 / isSystemClient) is not a real goods-owner
  // target, and a retired (INACTIVE) client must not acquire new stock.
  const targets = (clients ?? []).filter((c) => !c.isSystemClient && c.state === 'ACTIVE');
  const canSubmit = targetClientId !== '' && !changeClient.isPending;

  const handleSubmit = async () => {
    if (!canSubmit) return;
    try {
      await changeClient.mutateAsync({
        id: unitLoadId,
        targetClientId: Number(targetClientId),
        activityCode: reason.trim() || undefined,
      });
      toast.success('Owner changed', { description: unitLoadLabel });
      onOpenChange(false);
    } catch {
      // api-client already surfaces a toast for the failed request — incl.
      // the 409 total-refusal and 403 OPS-principal-gate cases, whose detail
      // strings are written for humans.
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Change owner — {unitLoadLabel}</DialogTitle>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-2">
            <Label>New owner</Label>
            <Select value={targetClientId} onValueChange={setTargetClientId}>
              <SelectTrigger>
                <SelectValue placeholder="Select an owner" />
              </SelectTrigger>
              <SelectContent>
                {targets.map((c) => (
                  <SelectItem key={c.id} value={String(c.id)}>
                    {c.name} · {c.number}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-2">
            <Label htmlFor="change-owner-reason">Reason (optional)</Label>
            <Input
              id="change-owner-reason"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="e.g. reassigned to 3PL customer"
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button onClick={handleSubmit} disabled={!canSubmit}>
            {changeClient.isPending ? 'Saving…' : 'Change owner'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Lock dialog — applies a pallet-level lock (A2-3, defaults to GENERAL(1)
 * server-side) to a whole unit load, excluding every constituent stock unit
 * from selection until unlocked.
 */
function LockUnitLoadDialog({
  open,
  onOpenChange,
  unitLoadId,
  unitLoadLabel,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  unitLoadId: number;
  unitLoadLabel: string;
}) {
  const lockUnitLoad = useLockUnitLoad();

  const handleConfirm = async () => {
    try {
      await lockUnitLoad.mutateAsync({ id: unitLoadId });
      toast.success('Unit load locked', { description: unitLoadLabel });
      onOpenChange(false);
    } catch {
      // api-client already surfaces a toast for the failed request.
    }
  };

  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Lock unit load?</AlertDialogTitle>
          <AlertDialogDescription>
            {`This locks "${unitLoadLabel}" and excludes it (and its stock) from picking until it is unlocked.`}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction onClick={handleConfirm} disabled={lockUnitLoad.isPending}>
            Lock
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

/**
 * Send-to-clearing dialog — moves a unit load to the facility's flagged
 * clearing location and recursively locks it there for disposition (A2-1).
 * On 409 `not-configured` (no location flagged `isClearing`) the api-client's
 * global toast surfaces the message; no special handling here.
 */
function TransferToClearingDialog({
  open,
  onOpenChange,
  unitLoadId,
  unitLoadLabel,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  unitLoadId: number;
  unitLoadLabel: string;
}) {
  const transferToClearing = useTransferToClearing();

  const handleConfirm = async () => {
    try {
      await transferToClearing.mutateAsync({ id: unitLoadId });
      toast.success('Sent to clearing', { description: unitLoadLabel });
      onOpenChange(false);
    } catch {
      // api-client already surfaces a toast for the failed request, incl. the
      // 409 'not-configured' case when no clearing location is flagged.
    }
  };

  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Send to clearing?</AlertDialogTitle>
          <AlertDialogDescription>
            {`Moves "${unitLoadLabel}" to the facility's clearing location and locks it there for disposition. This cannot be undone via this action — use Unlock to release it once it arrives.`}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction onClick={handleConfirm} disabled={transferToClearing.isPending}>
            Send to clearing
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

/**
 * Clear-stock (extinguish) dialog — creates a stock-clearance pick for the
 * FULL available amount of every stock unit on this unit load (row 20). The
 * created pick reserves the stock immediately; it is picked through the
 * normal fulfillment flow like any other pick order (surfaced as an EXT-
 * order in the work inbox), not deleted here. Gated separately from
 * inventory-write on fulfillment-write, since this creates a fulfillment
 * pick order rather than mutating the stock unit directly.
 */
function ExtinguishDialog({
  open,
  onOpenChange,
  unitLoadId,
  unitLoadLabel,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  unitLoadId: number;
  unitLoadLabel: string;
}) {
  const extinguish = useExtinguishStock();

  const handleConfirm = async () => {
    try {
      await extinguish.mutateAsync({ unitLoadId });
      onOpenChange(false);
    } catch {
      // useExtinguishStock / api-client already surface a toast for the failed request.
    }
  };

  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Clear stock?</AlertDialogTitle>
          <AlertDialogDescription>
            {`Creates a stock-clearance pick for the full available amount on "${unitLoadLabel}". The stock is reserved immediately and cleared through the normal picking flow.`}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction onClick={handleConfirm} disabled={extinguish.isPending}>
            Clear stock
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

/**
 * Per-LPN row actions — documents (Content list / Label ZPL, always visible —
 * read-only, no write-perm gate), change owner, carrier toggle, and the A2
 * pallet-lock affordances (Lock/Unlock/Send to clearing, all write-gated).
 * `GroupLpn` already carries the constituent stock unit's `unitLoadId`
 * (threaded from `StockUnitResponse.unitLoadId` in `toItemLocationGroups`),
 * so every action targets it directly with no extra lookup. `unitLoad` (the pallet's
 * own lockType/isCarrier, sourced from a per-group `GET /unit-loads?locationId=` fetch
 * -- StockUnitResponse carries neither field) is undefined while that fetch is
 * loading; the carrier toggle and Lock/Unlock are both gated on the honest
 * current value (defaulting to "not carrier"/"unlocked" rather than guessing)
 * until it resolves.
 */
function UnitLoadRowActions({
  lpn,
  unitLoad,
  canWrite,
  canExtinguish,
}: {
  lpn: GroupLpn;
  unitLoad?: UnitLoadResponse;
  canWrite: boolean;
  /** fulfillment-write — separate from inventory-write since Clear stock
   *  creates a fulfillment pick order, not a direct stock mutation. */
  canExtinguish: boolean;
}) {
  const setCarrier = useSetUnitLoadCarrier();
  const unlockUnitLoad = useUnlockUnitLoad();
  const [changeOwnerOpen, setChangeOwnerOpen] = useState(false);
  const [lockOpen, setLockOpen] = useState(false);
  const [clearingOpen, setClearingOpen] = useState(false);
  const [extinguishOpen, setExtinguishOpen] = useState(false);

  const isLocked = (unitLoad?.lockType ?? 0) !== 0;
  const isCarrier = unitLoad?.isCarrier ?? false;

  const handleCarrier = async (isCarrier: boolean) => {
    try {
      await setCarrier.mutateAsync({ id: lpn.unitLoadId, isCarrier });
      toast.success(isCarrier ? 'Marked as carrier' : 'Unmarked carrier', {
        description: lpn.lpn,
      });
    } catch {
      // api-client already surfaces a toast for the failed request.
    }
  };

  const handleUnlock = async () => {
    try {
      await unlockUnitLoad.mutateAsync(lpn.unitLoadId);
      toast.success('Unit load unlocked', { description: lpn.lpn });
    } catch {
      // api-client already surfaces a toast for the failed request.
    }
  };

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <button
            type="button"
            aria-label="Unit load actions"
            className="flex size-7 flex-none items-center justify-center rounded-md text-muted-foreground/60 transition-colors hover:bg-accent hover:text-foreground"
          >
            <MoreVertical className="size-[15px]" />
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem
            data-testid={`doc-content-list-btn-${lpn.id}`}
            onSelect={() => viewPdf(`/api/v1/unit-loads/${lpn.unitLoadId}/content-list.pdf`)}
          >
            Content list
          </DropdownMenuItem>
          <DropdownMenuItem
            data-testid={`doc-content-list-archive-btn-${lpn.id}`}
            onSelect={() =>
              archiveDocument(`/api/v1/unit-loads/${lpn.unitLoadId}/content-list.pdf`)
            }
          >
            Archive content list
          </DropdownMenuItem>
          <DropdownMenuItem
            data-testid={`doc-label-btn-${lpn.id}`}
            onSelect={() =>
              saveZpl(`/api/v1/unit-loads/${lpn.unitLoadId}/label.zpl`, `ul-${lpn.lpn}.zpl`)
            }
          >
            Label (ZPL)
          </DropdownMenuItem>
          <DropdownMenuItem
            data-testid={`doc-label-archive-btn-${lpn.id}`}
            onSelect={() => archiveDocument(`/api/v1/unit-loads/${lpn.unitLoadId}/label.zpl`)}
          >
            Archive label
          </DropdownMenuItem>
          {canWrite && (
            <>
              <DropdownMenuItem onSelect={() => setChangeOwnerOpen(true)}>
                Change owner…
              </DropdownMenuItem>
              {!isCarrier && (
                <DropdownMenuItem data-testid="ul-carrier-btn" onSelect={() => handleCarrier(true)}>
                  Mark as carrier
                </DropdownMenuItem>
              )}
              {isCarrier && (
                <DropdownMenuItem data-testid="ul-uncarrier-btn" onSelect={() => handleCarrier(false)}>
                  Unmark carrier
                </DropdownMenuItem>
              )}
              {!isLocked && (
                <DropdownMenuItem data-testid="ul-lock-btn" onSelect={() => setLockOpen(true)}>
                  Lock
                </DropdownMenuItem>
              )}
              {isLocked && (
                <DropdownMenuItem data-testid="ul-unlock-btn" onSelect={handleUnlock}>
                  Unlock
                </DropdownMenuItem>
              )}
              <DropdownMenuItem data-testid="ul-clearing-btn" onSelect={() => setClearingOpen(true)}>
                Send to clearing…
              </DropdownMenuItem>
            </>
          )}
          {canExtinguish && (
            <DropdownMenuItem
              data-testid="ul-extinguish-btn"
              className="text-destructive"
              onSelect={() => setExtinguishOpen(true)}
            >
              Clear stock (extinguish)…
            </DropdownMenuItem>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
      {canWrite && (
        <>
          <ChangeOwnerDialog
            key={`change-owner:${lpn.id}:${changeOwnerOpen}`}
            open={changeOwnerOpen}
            onOpenChange={setChangeOwnerOpen}
            unitLoadId={lpn.unitLoadId}
            unitLoadLabel={lpn.lpn}
          />
          <LockUnitLoadDialog
            key={`lock:${lpn.id}:${lockOpen}`}
            open={lockOpen}
            onOpenChange={setLockOpen}
            unitLoadId={lpn.unitLoadId}
            unitLoadLabel={lpn.lpn}
          />
          <TransferToClearingDialog
            key={`clearing:${lpn.id}:${clearingOpen}`}
            open={clearingOpen}
            onOpenChange={setClearingOpen}
            unitLoadId={lpn.unitLoadId}
            unitLoadLabel={lpn.lpn}
          />
        </>
      )}
      {canExtinguish && (
        <ExtinguishDialog
          key={`extinguish:${lpn.id}:${extinguishOpen}`}
          open={extinguishOpen}
          onOpenChange={setExtinguishOpen}
          unitLoadId={lpn.unitLoadId}
          unitLoadLabel={lpn.lpn}
        />
      )}
    </>
  );
}

export function InventoryDetail({ group }: { group: ItemLocationGroup }) {
  const isHeld = group.status.status === 'Hold';
  const lot = groupLotLabel(group);
  const expHex = expiryHex(group.earliestDaysLeft);

  const { hasPermission } = usePermissions();
  const canWrite = hasPermission('inventory-write');
  const canExtinguish = hasPermission('fulfillment-write');

  const [adjustOpen, setAdjustOpen] = useState(false);
  const [moveOpen, setMoveOpen] = useState(false);
  const [holdOpen, setHoldOpen] = useState(false);

  // Real via GoodsReceiptLookup (a representative constituent's receipt) —
  // honest "—" when the group's stock was never received through a GR.
  const received = fmtIso(group.receivedAt);
  const supplier = group.supplierName ?? '—';
  const sourceAsn = group.sourceAsn ?? '—';
  // Real via FixAssignment.minAmount — honest "—" when no fix-assignment exists.
  const reorderPoint = group.reorderPoint === null ? '—' : group.reorderPoint.toLocaleString();

  // Real per-location movement ledger + client-side running balance, newest
  // first — unwind from the group's current on-hand back through each event.
  const { data: journalRows, isLoading: journalsLoading } = useJournals({
    location: group.location,
    productNumber: group.sku,
  });

  // A2: pallet-level lock state per LPN row. StockUnitResponse carries no
  // unit-load lock field, so this is one fetch per selected group (every
  // unit load at the group's location) rather than one per LPN row.
  const { data: unitLoadsAtLocation } = useUnitLoadsByLocation(group.locationId);
  const unitLoadsById = new Map((unitLoadsAtLocation ?? []).map((ul) => [ul.id, ul]));
  const ledger = journalRows.reduce<
    { entry: JournalEntry; delta: number; balance: number }[]
  >((acc, e) => {
    // balance BEFORE this row = balance AFTER the previous (newer) row, minus
    // its delta; the very first (newest) row starts from the current on-hand.
    // NOTE: this is an approximation unwound from today's on-hand, not a true
    // historical ledger — the demo's historical picks don't decrement
    // StockUnit.amount, so older balances won't necessarily reconcile to zero.
    const bal = acc.length > 0 ? acc[acc.length - 1].balance - acc[acc.length - 1].delta : group.onHand;
    const delta = signedDelta(e, group.location);
    acc.push({ entry: e, delta, balance: bal });
    return acc;
  }, []);

  return (
    <div className="rounded-2xl border border-border bg-card">
      <div className="p-6">
        {/* Header */}
        <div className="mb-5 flex items-start gap-4">
          <div className="flex size-[52px] flex-none items-center justify-center rounded-xl border border-border bg-background text-muted-foreground/50">
            <Box className="size-6" strokeWidth={1.5} />
          </div>
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-3">
              <h1 className="font-display text-[23px] text-foreground">{group.name}</h1>
              <span
                className={cn(
                  'rounded-full px-2.5 py-1 text-[11.5px] font-bold',
                  toneTextClass(group.status.tone),
                  toneBgClass(group.status.tone),
                )}
              >
                {group.status.status}
              </span>
            </div>
            <p className="mt-1.5 text-[13.5px] text-foreground/70">
              <span className="numeric text-foreground/90">{group.sku}</span> · at{' '}
              <span className="numeric text-foreground/90">{group.location}</span>
              {group.locType !== '—' && <> · {group.locType}</>}
            </p>
          </div>
          <div className="flex flex-none gap-2">
            <button
              type="button"
              disabled={!canWrite}
              onClick={() => setAdjustOpen(true)}
              className="h-9 rounded-[9px] bg-primary px-3.5 text-[13px] font-bold text-primary-foreground transition-opacity hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40"
            >
              Adjust
            </button>
            <button
              type="button"
              disabled={!canWrite}
              onClick={() => setMoveOpen(true)}
              className="h-9 rounded-[9px] border border-border bg-card px-[13px] text-[13px] font-medium text-foreground/85 hover:bg-accent disabled:cursor-not-allowed disabled:opacity-40"
            >
              Move
            </button>
            <button
              type="button"
              disabled={!canWrite}
              onClick={() => setHoldOpen(true)}
              className={cn(
                'h-9 rounded-[9px] border bg-card px-[13px] text-[13px] font-medium hover:bg-accent disabled:cursor-not-allowed disabled:opacity-40',
                isHeld
                  ? 'border-primary/30 text-primary'
                  : 'border-destructive/20 text-destructive',
              )}
            >
              {isHeld ? 'Release' : 'Hold'}
            </button>
          </div>
        </div>

        {/* Each dialog remounts (via `key`) on every open transition, for the
            same group, so its internal form state always starts fresh
            without a reset effect. */}
        <AdjustDialog
          key={`adjust:${group.key}:${adjustOpen}`}
          open={adjustOpen}
          onOpenChange={setAdjustOpen}
          group={group}
        />
        <MoveDialog
          key={`move:${group.key}:${moveOpen}`}
          open={moveOpen}
          onOpenChange={setMoveOpen}
          group={group}
        />
        <HoldDialog
          key={`hold:${group.key}:${holdOpen}`}
          open={holdOpen}
          onOpenChange={setHoldOpen}
          group={group}
          isHeld={isHeld}
        />

        {/* Qty cards + Lot & quality */}
        <div className="mb-4 flex flex-col gap-4 lg:flex-row lg:items-stretch">
          <div className="flex w-full flex-none gap-3 lg:w-[300px]">
            <div className="flex-1">
              <StatCard label="On hand" value={group.onHand.toLocaleString()} sub="units" big />
            </div>
            <div className="flex flex-1 flex-col gap-3">
              <StatCard
                label="Available"
                value={group.available.toLocaleString()}
                valueClass="text-primary"
              />
              <StatCard
                label="Allocated"
                value={group.reserved.toLocaleString()}
                valueClass="text-warning-foreground"
              />
            </div>
          </div>

          <section className="min-w-0 flex-1 rounded-2xl border border-border bg-card p-5">
            <h2 className="mb-4 text-[14px] font-semibold text-foreground">Lot &amp; quality</h2>
            <div className="grid grid-cols-2 gap-x-5 gap-y-3.5 sm:grid-cols-3">
              <LotField label="Lot" value={lot} />
              <LotField label="Earliest expiry" value={fmtIso(group.earliestExpiry)} valueHex={expHex} />
              <LotField
                label="Days left"
                value={group.earliestDaysLeft === null ? '—' : `${group.earliestDaysLeft}d`}
                valueHex={expHex}
              />
              {/* Real via GoodsReceiptLookup / FixAssignment — honest "—" when no
                  constituent was GR-received or no fix-assignment exists. Per B7,
                  receivedAt is the operator-entered arrival date (receiptDate) when
                  set, else the receipt record's creation time — labeled "Arrived"
                  to reflect that semantics, not a raw system timestamp. */}
              <LotField label="Arrived" value={received} />
              <LotField label="Supplier" value={supplier} mono={false} />
              <LotField label="Source ASN" value={sourceAsn} />
              <LotField label="Reorder point" value={reorderPoint} />
            </div>
            {isHeld && (
              <div className="mt-4 flex items-start gap-2 rounded-xl border border-destructive/30 bg-background p-3">
                <span className="mt-1 size-[7px] flex-none rounded-full bg-destructive" />
                <span className="text-[12px] leading-relaxed text-foreground/75">
                  Stock at this location is locked. Awaiting inspection / disposition before release.
                </span>
              </div>
            )}
          </section>
        </div>

        {/* Stored units — LPN demoted here */}
        <section className="mb-4 overflow-hidden rounded-2xl border border-border bg-card">
          <div className="flex items-center justify-between border-b border-border px-[18px] py-3.5">
            <h2 className="text-[14px] font-semibold text-foreground">Stored units</h2>
            <span className="numeric text-[10.5px] text-muted-foreground/70">
              {group.lpnTracked
                ? `${group.lpns.length} ${group.lpns.length === 1 ? 'LPN' : 'LPNs'} · ${group.onHand.toLocaleString()} units`
                : `Loose · lot ${lot}`}
            </span>
          </div>
          {group.lpnTracked ? (
            <div>
              <div className="grid grid-cols-[1.2fr_1fr_1fr_80px_28px] gap-3 border-b border-border px-[18px] py-2.5">
                <span className="numeric text-[10px] tracking-wider text-muted-foreground/70">LPN / PALLET</span>
                <span className="numeric text-[10px] tracking-wider text-muted-foreground/70">LOT</span>
                <span className="numeric text-[10px] tracking-wider text-muted-foreground/70">EXPIRY</span>
                <span className="numeric text-right text-[10px] tracking-wider text-muted-foreground/70">QTY</span>
                <span />
              </div>
              {group.lpns.map((u) => {
                const unitLoad = unitLoadsById.get(u.unitLoadId);
                const isLocked = (unitLoad?.lockType ?? 0) !== 0;
                return (
                  <div
                    key={u.id}
                    className="grid grid-cols-[1.2fr_1fr_1fr_80px_28px] items-center gap-3 border-b border-border px-[18px] py-3 last:border-b-0"
                  >
                    <span className="flex min-w-0 items-center gap-2">
                      <span className="numeric truncate text-[12.5px] font-semibold text-foreground">
                        {u.lpn}
                      </span>
                      {/* A2: pallet-level lock — StatusPill only renders once
                          the per-group unit-load fetch resolves and confirms
                          lockType !== 0 (no fabricated pill while loading). */}
                      {isLocked && (
                        <span data-testid="ul-lock-pill">
                          <StatusPill label={unitLoad!.lockTypeName} tone="red" />
                        </span>
                      )}
                    </span>
                    <span className="numeric text-[12px] text-muted-foreground">{u.lot ?? '—'}</span>
                    <span className="numeric text-[12px]" style={{ color: expiryHex(u.daysLeft) }}>
                      {fmtIso(u.bestBefore)}
                    </span>
                    <span className="numeric text-right text-[13px] font-bold text-foreground">
                      {u.qty.toLocaleString()}
                    </span>
                    {/* Always rendered — the dropdown itself carries the
                        read-only document actions (Content list / Label ZPL)
                        regardless of inventory-write. The mutation-backed
                        items (change owner / carrier / lock / clearing) stay
                        gated inside on the same inventory-write permission as
                        the header Adjust/Move/Hold buttons. Backend
                        additionally requires an OPS principal for
                        change-client; the UI shows it to any writer and lets
                        the 403 explain (no principal-kind claim surface on
                        the frontend today — demo users are OPS). */}
                    <UnitLoadRowActions
                      lpn={u}
                      unitLoad={unitLoad}
                      canWrite={canWrite}
                      canExtinguish={canExtinguish}
                    />
                  </div>
                );
              })}
            </div>
          ) : (
            <div className="flex items-start gap-3 px-[18px] py-4">
              <div className="flex size-[34px] flex-none items-center justify-center rounded-[9px] border border-border bg-background text-muted-foreground/70">
                <Boxes className="size-[17px]" strokeWidth={1.8} />
              </div>
              <div className="text-[12.5px] leading-relaxed text-foreground/75">
                No unit-load breakdown — quantity is held against the item-lot at
                this location.
                <br />
                <span className="numeric text-muted-foreground">lot {lot}</span>
              </div>
            </div>
          )}
        </section>

        {/* Movement ledger — real journal rows for this item @ location, with
            a client-side running balance unwound from the current on-hand. */}
        <section className="overflow-hidden rounded-2xl border border-border bg-card">
          <div className="flex items-center justify-between border-b border-border px-[18px] py-3.5">
            <h2 className="text-[14px] font-semibold text-foreground">Movement ledger</h2>
            <span className="numeric text-[10.5px] text-muted-foreground/70">EVENT · QTY · RUNNING BALANCE</span>
          </div>
          {journalsLoading && ledger.length === 0 ? (
            <div className="px-[18px] py-8 text-center">
              <p className="text-[13px] text-muted-foreground/70">Loading ledger…</p>
            </div>
          ) : ledger.length === 0 ? (
            <div className="px-[18px] py-8 text-center">
              <p className="text-[13px] text-muted-foreground/70">No movement history yet.</p>
            </div>
          ) : (
            <>
              <div className="grid grid-cols-[84px_1fr_1.1fr_72px_84px] gap-3 border-b border-border px-[18px] py-2.5">
                <span className="numeric text-[10px] tracking-wider text-muted-foreground/70">DATE</span>
                <span className="numeric text-[10px] tracking-wider text-muted-foreground/70">EVENT</span>
                <span className="numeric text-[10px] tracking-wider text-muted-foreground/70">REFERENCE</span>
                <span className="numeric text-right text-[10px] tracking-wider text-muted-foreground/70">QTY</span>
                <span className="numeric text-right text-[10px] tracking-wider text-muted-foreground/70">BALANCE</span>
              </div>
              {ledger.map(({ entry, delta, balance }, i) => {
                const meta = recordTypeMeta(entry.recordType);
                return (
                  <div
                    key={i}
                    className="grid grid-cols-[84px_1fr_1.1fr_72px_84px] items-center gap-3 border-b border-border px-[18px] py-3 last:border-b-0"
                  >
                    <span className="numeric text-[12px] text-muted-foreground">
                      {new Date(entry.created).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}
                    </span>
                    <div className="flex items-center gap-2">
                      <span className="size-[7px] flex-none rounded-[2px]" style={{ background: meta.dotHex }} />
                      <span className="text-[12.5px] text-foreground/90">{meta.label}</span>
                    </div>
                    <span className="numeric text-[12px] text-muted-foreground">{entry.correlationId ?? '—'}</span>
                    <span
                      className="numeric text-right text-[13px] font-bold"
                      style={{ color: delta > 0 ? 'var(--info)' : delta < 0 ? 'var(--danger)' : 'var(--muted-foreground)' }}
                    >
                      {delta > 0 ? `+${delta}` : delta}
                    </span>
                    <span className="numeric text-right text-[13px] font-semibold text-foreground">
                      {balance.toLocaleString()}
                    </span>
                  </div>
                );
              })}
            </>
          )}
        </section>
      </div>
    </div>
  );
}
