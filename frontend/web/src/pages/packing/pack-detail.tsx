import { useEffect, useRef } from 'react';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Skeleton } from '@/components/ui/skeleton';
import { SectionCard } from '@/components/control/section-card';
import { StatusPill } from '@/components/control/status-pill';
import { usePickOrders } from '@/features/picking/use-pick-orders';
import { useDeliveryOrder } from '@/pages/orders/use-orders';
import type { ShippingUnit } from '@/types/shipments';
import { getShipmentStatus } from './packing-status';
import { PackForm } from './pack-form';
import { useOpenPacking, usePackShipment, useShipment } from './use-packing';

export type PackSelection =
  | { mode: 'ready'; pickOrderId: number; deliveryOrderId: number }
  // Null for an in-progress group shipment (Bulk Allocation Sprint C) -- it has member
  // orders, not one deliveryOrderId, and its pack detail is not built out here yet.
  | { mode: 'inProgress'; shipmentId: number; deliveryOrderId: number | null };

interface PackDetailProps {
  selection: PackSelection;
  canWrite: boolean;
}

/** Stable identity for the open-packing-once guard -- keys on the fields that
 * actually distinguish one ready selection from another. */
function readyKey(s: Extract<PackSelection, { mode: 'ready' }>): string {
  return `ready:${s.pickOrderId}`;
}

/** Read-only Unit #/Type/Weight table shared by the terminal (PACKED/650) summary and the
 *  Task 3 in-progress (PACKING/640, multi-call) summary -- same columns, different testid so
 *  the two states stay distinguishable in tests. */
function ShippingUnitsTable({ units, testId }: { units: ShippingUnit[]; testId: string }) {
  return (
    <Table data-testid={testId}>
      <TableHeader>
        <TableRow>
          <TableHead>Unit #</TableHead>
          <TableHead>Type</TableHead>
          <TableHead className="text-right">Weight</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {units.map((su) => (
          <TableRow key={su.id} data-testid={`shipping-unit-${su.id}`}>
            <TableCell className="font-mono text-[13px]">{su.shippingUnitNumber}</TableCell>
            <TableCell>{su.type}</TableCell>
            <TableCell className="text-right tabular-nums">{su.weight}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

/**
 * Pack detail pane (P4 Task 1) -- ports pack-drawer.tsx's behavior onto the
 * Control master-detail kit. Ready mode opens a Shipment for the delivery
 * order (once per selection, guarded by a ref keyed on selection identity so
 * re-selecting the same row never double-POSTs); in-progress mode fetches the
 * existing shipment fresh so units stay current after a pack. Contents to
 * verify always come from the pick order's PICKED(600) picks.
 */
export function PackDetail({ selection, canWrite }: PackDetailProps) {
  const openPacking = useOpenPacking();
  const packShipment = usePackShipment();

  const openedForRef = useRef<string | undefined>(undefined);
  const openMutate = openPacking.mutate;
  useEffect(() => {
    if (selection.mode !== 'ready') {
      openedForRef.current = undefined;
      return;
    }
    const key = readyKey(selection);
    if (openedForRef.current === key) return;
    openedForRef.current = key;
    openMutate(selection.deliveryOrderId);
  }, [selection, openMutate]);

  // inProgress mode: fetch the freshest shipment (units stay current after packs).
  const fresh = useShipment(selection.mode === 'inProgress' ? selection.shipmentId : undefined);

  // Active shipment: the pack mutation's result wins once it exists (freshest truth after
  // a confirm flips PACKING(640) -> PACKED(650)); else ready mode -> open mutation's result,
  // inProgress -> the fresh fetch. Both mutations stay mounted across selections (this pane
  // itself owns them, like ShipDetail), so a guard on the pack result stops a previous
  // selection's packed summary from leaking into a newly-selected one (I-1). An inProgress
  // selection is keyed on the shipment's own id, not deliveryOrderId -- two different group
  // shipments (Bulk Allocation Sprint C) both carry deliveryOrderId: null, so that comparison
  // would otherwise match any coexisting group shipment's stale result.
  const packResultMatches =
    packShipment.data != null &&
    (selection.mode === 'inProgress'
      ? packShipment.data.id === selection.shipmentId
      : packShipment.data.deliveryOrderId === selection.deliveryOrderId);
  const packResult = packResultMatches ? packShipment.data : undefined;
  const active = packResult ?? (selection.mode === 'ready' ? openPacking.data : fresh.data);

  // Contents to verify always come from the pick order's PICKED(600) picks. A null
  // selection.deliveryOrderId (an inProgress group shipment) has no single delivery order to
  // match picks against -- matching on null would otherwise pick up any coexisting EXTINGUISH
  // pick order (deliveryOrderId: null too), so the lookup requires a real id.
  const pickOrders = usePickOrders();
  const po =
    selection.deliveryOrderId != null
      ? pickOrders.data?.find((p) => p.deliveryOrderId === selection.deliveryOrderId)
      : undefined;
  const contents = po?.picks.filter((pk) => pk.state === 600) ?? [];

  const { data: deliveryOrder } = useDeliveryOrder(selection.deliveryOrderId ?? undefined);

  const deliveryOrderNumber = active?.deliveryOrderNumber ?? po?.deliveryOrderNumber;
  const status = active ? getShipmentStatus(active) : undefined;

  return (
    <div className="space-y-4">
      <SectionCard>
        <div className="flex items-center gap-3">
          <h1 className="font-display numeric text-[20px] font-bold text-foreground">
            {deliveryOrderNumber ?? '—'}
            <span className="mx-2 text-foreground/40">{'→'}</span>
            {active?.shipmentNumber ?? 'opening…'}
          </h1>
          {status && <StatusPill label={status.label} tone={status.tone} />}
        </div>
        <p className="mt-1 text-[13px] text-foreground/70">
          Pack the picked contents into shipping units.
        </p>

        {deliveryOrder?.packingHint && (
          <div
            className="mt-4 rounded-md border border-signal/25 bg-signal/10 p-3"
            data-testid="pack-pane-hint"
          >
            <p className="text-[13px] font-medium text-foreground">{deliveryOrder.packingHint}</p>
          </div>
        )}
      </SectionCard>

      <SectionCard title="Contents to verify" noPad>
        <Table data-testid="pack-contents">
          <TableHeader>
            <TableRow>
              <TableHead>Item</TableHead>
              <TableHead className="text-right">Amount</TableHead>
              <TableHead>Lot</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {contents.map((pk) => (
              <TableRow key={pk.id} data-testid={`pack-content-${pk.id}`}>
                <TableCell className="font-mono text-[13px]">{pk.itemDataNumber}</TableCell>
                <TableCell className="text-right tabular-nums">{pk.pickedAmount}</TableCell>
                <TableCell>{pk.lotNumber ?? '—'}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </SectionCard>

      {!active ? (
        <SectionCard>
          <div className="space-y-3">
            <Skeleton className="h-6 w-1/2" />
            <Skeleton className="h-32 w-full" />
          </div>
        </SectionCard>
      ) : active.state === 650 ? (
        <SectionCard title="Shipping units" noPad>
          <ShippingUnitsTable units={active.shippingUnits} testId="packed-summary" />
        </SectionCard>
      ) : active.state === 640 ? (
        <>
          {/* Task 3 (multi-call packing): a `complete=false` pack() call persists units without
           * flipping the shipment to PACKED, so a shipment can sit at PACKING with units already
           * on it. Render the read-only progress summary ABOVE the form (both visible) so a
           * second pack() call shows what's already boxed instead of hiding it until the final
           * call. */}
          {active.shippingUnits.length > 0 && (
            <SectionCard title="Packed so far" noPad>
              <ShippingUnitsTable units={active.shippingUnits} testId="packed-units-progress" />
            </SectionCard>
          )}
          {canWrite && (
            <PackForm
              isPending={packShipment.isPending}
              onConfirm={(weight, type) =>
                packShipment.mutate({ shipmentId: active.id, body: { weight, type } })
              }
              // No "close" concept in a detail pane (unlike the retired Sheet
              // drawer) -- Cancel is a no-op; PackForm keeps its own local draft.
              onCancel={() => {}}
            />
          )}
        </>
      ) : null}
    </div>
  );
}
