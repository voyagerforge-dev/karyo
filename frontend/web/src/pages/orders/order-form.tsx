import { useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Button } from '@/components/ui/button';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  useCreateDeliveryOrder,
  useUpdateDeliveryOrder,
  useOrderStrategies,
} from './use-orders';
import { ProductPicker, type PickedProduct } from './product-picker';
import { LocationPicker, type PickedLocation } from '../receiving/location-picker';
import type { DeliveryOrderResponse } from '@/types/orders';

interface OrderFormProps {
  /** Existing order for header-edit mode (CREATED only); omit for create mode */
  order?: DeliveryOrderResponse;
  onClose: () => void;
}

interface LineDraft {
  /** Local key for React list rendering */
  key: number;
  product: PickedProduct | null;
  amount: string;
  lotNumber: string;
}

interface FormErrors {
  lines?: string;
  prio?: string;
}

let lineKeySeq = 0;
function newLine(): LineDraft {
  lineKeySeq += 1;
  return { key: lineKeySeq, product: null, amount: '', lotNumber: '' };
}

/**
 * Tri-state payload builder for the five `Patchable<String>` order-header fields (notes/
 * pickingHint/packingHint/shippingHint/externalNumber, D2 2026-07-25): a trimmed-empty value
 * clears a field that previously had content (explicit `null` -- the backend now supports
 * this), but is omitted (`undefined`) when there was nothing to clear (untouched empty stays
 * empty). A non-empty trimmed value is always sent as a set, matching the pre-D2 behavior.
 */
export function patchField(
  original: string | null | undefined,
  current: string,
): string | null | undefined {
  const trimmed = current.trim();
  if (trimmed === '') {
    return original ? null : undefined;
  }
  return trimmed;
}

/**
 * Tri-state payload builder for id fields backed by `Patchable<Long>` (:1457, 2026-08-17):
 * mirrors [patchField]'s shape for numeric ids picked via `LocationPicker`. `current == null`
 * (nothing picked / the picker was cleared) sends explicit `null` when [original] had a value
 * (clears it), or `undefined` when there was nothing to clear (untouched-empty stays absent).
 * A non-null [current] is always sent as a value -- whether newly picked or unchanged from
 * [original] -- matching [patchField]'s non-empty-string case.
 */
export function patchIdField(
  original: number | null | undefined,
  current: number | null,
): number | null | undefined {
  if (current == null) {
    return original != null ? null : undefined;
  }
  return current;
}

export function OrderForm({ order, onClose }: OrderFormProps) {
  const isEdit = !!order;
  const createMutation = useCreateDeliveryOrder();
  const updateMutation = useUpdateDeliveryOrder();
  const { data: strategies, isLoading: strategiesLoading } = useOrderStrategies();

  const [orderNumber, setOrderNumber] = useState(order?.orderNumber ?? '');
  const [customerName, setCustomerName] = useState(order?.customerName ?? '');
  const [externalNumber, setExternalNumber] = useState(order?.externalNumber ?? '');
  const [deliveryDate, setDeliveryDate] = useState(order?.deliveryDate ?? '');
  const [prio, setPrio] = useState(order?.prio != null ? String(order.prio) : '50');
  const [notes, setNotes] = useState(order?.notes ?? '');
  const [pickingHint, setPickingHint] = useState(order?.pickingHint ?? '');
  const [packingHint, setPackingHint] = useState(order?.packingHint ?? '');
  const [shippingHint, setShippingHint] = useState(order?.shippingHint ?? '');
  const [strategyId, setStrategyId] = useState(
    order?.orderStrategyId != null ? String(order.orderStrategyId) : '',
  );
  const [senderName, setSenderName] = useState(order?.senderName ?? '');
  const [destination, setDestination] = useState<PickedLocation | null>(
    order?.destinationLocationId != null
      ? { id: order.destinationLocationId, name: order.destinationLocationName ?? '' }
      : null,
  );
  const [lines, setLines] = useState<LineDraft[]>(() => [newLine()]);
  const [errors, setErrors] = useState<FormErrors>({});

  function setLine(key: number, patch: Partial<LineDraft>) {
    setLines((prev) => prev.map((l) => (l.key === key ? { ...l, ...patch } : l)));
  }

  function validate(): boolean {
    const next: FormErrors = {};
    if (!isEdit) {
      const valid = lines.filter((l) => l.product && Number(l.amount) > 0);
      if (valid.length === 0) {
        next.lines = 'At least one line with a product and a positive amount is required';
      } else if (lines.some((l) => l.product && !(Number(l.amount) > 0))) {
        next.lines = 'Every line needs a positive amount';
      } else if (lines.some((l) => !l.product && (l.amount || l.lotNumber))) {
        next.lines = 'Every line needs a product';
      }
    }
    if (prio !== '' && Number.isNaN(Number(prio))) next.prio = 'Priority must be a number';
    setErrors(next);
    return Object.keys(next).length === 0;
  }

  function handleSubmit() {
    if (!validate()) return;

    if (isEdit) {
      updateMutation.mutate(
        {
          id: order.id,
          customerName: customerName.trim() || undefined,
          externalNumber: patchField(order.externalNumber, externalNumber),
          deliveryDate: deliveryDate || undefined,
          prio: prio ? Number(prio) : undefined,
          notes: patchField(order.notes, notes),
          pickingHint: patchField(order.pickingHint, pickingHint),
          packingHint: patchField(order.packingHint, packingHint),
          shippingHint: patchField(order.shippingHint, shippingHint),
          orderStrategyId: strategyId ? Number(strategyId) : undefined,
          destinationLocationId: patchIdField(
            order.destinationLocationId,
            destination ? destination.id : null,
          ),
          senderName: patchField(order.senderName, senderName),
        },
        { onSuccess: () => onClose() },
      );
    } else {
      createMutation.mutate(
        {
          orderNumber: orderNumber.trim() || undefined,
          customerName: customerName.trim() || undefined,
          externalNumber: externalNumber.trim() || undefined,
          deliveryDate: deliveryDate || undefined,
          prio: prio ? Number(prio) : 50,
          notes: notes.trim() || undefined,
          pickingHint: pickingHint.trim() || undefined,
          packingHint: packingHint.trim() || undefined,
          shippingHint: shippingHint.trim() || undefined,
          orderStrategyId: strategyId ? Number(strategyId) : undefined,
          destinationLocationId: destination ? destination.id : undefined,
          senderName: senderName.trim() || undefined,
          lines: lines
            .filter((l) => l.product)
            .map((l) => ({
              itemDataId: l.product!.id,
              amount: Number(l.amount),
              lotNumber: l.lotNumber.trim() || undefined,
            })),
        },
        { onSuccess: () => onClose() },
      );
    }
  }

  const isSubmitting = createMutation.isPending || updateMutation.isPending;

  return (
    <div className="space-y-6">
      {/* Order number (create only -- generated when left empty) */}
      {!isEdit && (
        <div className="space-y-2">
          <Label htmlFor="order-number">Order #</Label>
          <Input
            id="order-number"
            value={orderNumber}
            onChange={(e) => setOrderNumber(e.target.value)}
            placeholder="Generated if empty"
          />
        </div>
      )}

      {/* Customer */}
      <div className="space-y-2">
        <Label htmlFor="order-customer">Customer</Label>
        <Input
          id="order-customer"
          value={customerName}
          onChange={(e) => setCustomerName(e.target.value)}
          placeholder="Customer name"
        />
      </div>

      {/* External # + Delivery date */}
      <div className="grid grid-cols-2 gap-4">
        <div className="space-y-2">
          <Label htmlFor="order-external">External #</Label>
          <Input
            id="order-external"
            value={externalNumber}
            onChange={(e) => setExternalNumber(e.target.value)}
            placeholder="ERP reference"
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="order-delivery-date">Delivery Date</Label>
          <Input
            id="order-delivery-date"
            type="date"
            value={deliveryDate}
            onChange={(e) => setDeliveryDate(e.target.value)}
          />
        </div>
      </div>

      {/* Prio + Strategy */}
      <div className="grid grid-cols-2 gap-4">
        <div className="space-y-2">
          <Label htmlFor="order-prio">Priority</Label>
          <Input
            id="order-prio"
            type="number"
            value={prio}
            onChange={(e) => setPrio(e.target.value)}
            placeholder="50 = normal"
          />
          {errors.prio && <p className="text-sm text-destructive">{errors.prio}</p>}
        </div>
        <div className="space-y-2">
          <Label>Strategy</Label>
          <Select value={strategyId} onValueChange={setStrategyId}>
            <SelectTrigger>
              <SelectValue
                placeholder={strategiesLoading ? 'Loading...' : 'Default'}
              />
            </SelectTrigger>
            <SelectContent>
              {strategies?.map((s) => (
                <SelectItem key={s.id} value={String(s.id)}>
                  {s.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </div>

      {/* Destination + Sender */}
      <div className="grid grid-cols-2 gap-4">
        <div className="space-y-2">
          <Label htmlFor="order-destination">Destination</Label>
          <LocationPicker
            inputId="order-destination"
            value={destination}
            onChange={setDestination}
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="order-sender">Sender</Label>
          <Input
            id="order-sender"
            value={senderName}
            onChange={(e) => setSenderName(e.target.value)}
            placeholder="Party named as sender"
          />
        </div>
      </div>

      {/* Notes */}
      <div className="space-y-2">
        <Label htmlFor="order-notes">Notes</Label>
        <Textarea
          id="order-notes"
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          placeholder="Optional notes"
          rows={2}
        />
      </div>

      {/* Handling hints -- shown to picker/packer/dispatch during fulfillment */}
      <div className="space-y-3">
        <h3 className="text-sm font-medium">Handling hints</h3>
        <div className="space-y-2">
          <Label htmlFor="order-picking-hint">Picking hint</Label>
          <Textarea
            id="order-picking-hint"
            rows={2}
            value={pickingHint}
            onChange={(e) => setPickingHint(e.target.value)}
            maxLength={500}
            placeholder="Shown to the picker"
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="order-packing-hint">Packing hint</Label>
          <Textarea
            id="order-packing-hint"
            rows={2}
            value={packingHint}
            onChange={(e) => setPackingHint(e.target.value)}
            maxLength={500}
            placeholder="Shown at the pack station"
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="order-shipping-hint">Shipping hint</Label>
          <Textarea
            id="order-shipping-hint"
            rows={2}
            value={shippingHint}
            onChange={(e) => setShippingHint(e.target.value)}
            maxLength={500}
            placeholder="Shown at dispatch"
          />
        </div>
      </div>

      {/* Lines editor (create only -- line edits are not supported in v1.2) */}
      {!isEdit ? (
        <div className="space-y-3">
          <h3 className="text-sm font-medium">Lines</h3>
          <div className="space-y-3">
            {lines.map((line, index) => (
              <div
                key={line.key}
                className="grid grid-cols-[1fr_90px_110px_32px] items-start gap-2"
                data-testid={`order-line-${index}`}
              >
                <div className="space-y-1">
                  {index === 0 && (
                    <Label htmlFor={`line-product-${line.key}`}>Product</Label>
                  )}
                  <ProductPicker
                    inputId={`line-product-${line.key}`}
                    value={line.product}
                    onChange={(product) => setLine(line.key, { product })}
                  />
                </div>
                <div className="space-y-1">
                  {index === 0 && (
                    <Label htmlFor={`line-amount-${line.key}`}>Amount</Label>
                  )}
                  <Input
                    id={`line-amount-${line.key}`}
                    type="number"
                    min="0"
                    value={line.amount}
                    onChange={(e) => setLine(line.key, { amount: e.target.value })}
                    placeholder="Qty"
                  />
                </div>
                <div className="space-y-1">
                  {index === 0 && (
                    <Label htmlFor={`line-lot-${line.key}`}>Lot</Label>
                  )}
                  <Input
                    id={`line-lot-${line.key}`}
                    value={line.lotNumber}
                    onChange={(e) => setLine(line.key, { lotNumber: e.target.value })}
                    placeholder="Optional"
                  />
                </div>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  className={index === 0 ? 'mt-6' : ''}
                  onClick={() =>
                    setLines((prev) =>
                      prev.length > 1 ? prev.filter((l) => l.key !== line.key) : prev,
                    )
                  }
                  disabled={lines.length === 1}
                >
                  <Trash2 className="size-4" />
                  <span className="sr-only">Remove line {index + 1}</span>
                </Button>
              </div>
            ))}
          </div>
          {errors.lines && <p className="text-sm text-destructive">{errors.lines}</p>}
          <Button
            type="button"
            size="sm"
            variant="outline"
            onClick={() => setLines((prev) => [...prev, newLine()])}
          >
            <Plus className="size-4" />
            Add line
          </Button>
        </div>
      ) : (
        <p className="text-sm text-muted-foreground">
          Lines cannot be edited after creation — cancel the order and recreate it
          to change lines.
        </p>
      )}

      {/* Submit */}
      <div className="flex justify-end gap-2 border-t pt-4">
        <Button variant="outline" onClick={onClose}>
          Cancel
        </Button>
        <Button
          onClick={handleSubmit}
          disabled={isSubmitting}
          data-testid="order-form-submit"
        >
          {isSubmitting ? 'Saving...' : isEdit ? 'Save' : 'Create Order'}
        </Button>
      </div>
    </div>
  );
}
