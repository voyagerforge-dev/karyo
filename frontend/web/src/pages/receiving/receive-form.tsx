import { useState } from 'react';
import { toast } from 'sonner';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Button } from '@/components/ui/button';
import { ProductPicker, type PickedProduct } from '@/pages/orders/product-picker';
import { LocationPicker, type PickedLocation } from './location-picker';
import { ApiError } from '@/lib/api-client';
import { useReceiveLine } from './use-receiving';
import { RECEIPT_LOCK_LABELS } from './receiving-status';
import { GOODS_RECEIPT_TYPE, type AsnLineResponse } from '@/types/receiving';

export interface ReceivePrefill {
  /** Bound ASN line (locks the product, prefills remaining) */
  asnLine: AsnLineResponse | null;
}

interface ReceiveFormProps {
  receiptId: number;
  /** ASN line to receive against (null = blind line) */
  prefill: ReceivePrefill;
  /** Whether the receipt is still open for receiving (CREATED/STARTED) */
  disabled: boolean;
  /** Called after a successful receive so the parent can clear the prefill. */
  onReceived: () => void;
  /** Receipt type — drives the client-side lock default (RETOUR -> QUALITY FAULT). */
  receiptType?: number;
}

interface FormErrors {
  product?: string;
  amount?: string;
  location?: string;
}

/**
 * The receive-line form. When `prefill.asnLine` is set the product is locked to
 * that line and the amount defaults to its remaining quantity; otherwise it is a
 * blind line with a free product picker. An over-receipt 409 raises an inline
 * confirm that retries with `allowOverReceipt`.
 */
export function ReceiveForm({
  receiptId,
  prefill,
  disabled,
  onReceived,
  // NOTE: not yet passed by the workbench (Task 6 wires it) — default keeps
  // the client-side RETOUR lock default off until then.
  receiptType = GOODS_RECEIPT_TYPE.NORMAL,
}: ReceiveFormProps) {
  const receiveLine = useReceiveLine();

  // Blind-line product (ignored when an ASN line is prefilled).
  const [product, setProduct] = useState<PickedProduct | null>(null);
  const [amount, setAmount] = useState(
    prefill.asnLine ? String(prefill.asnLine.remainingAmount) : '',
  );
  const [location, setLocation] = useState<PickedLocation | null>(null);
  const [unitLoadLabel, setUnitLoadLabel] = useState('');
  const [lotNumber, setLotNumber] = useState(prefill.asnLine?.lotNumber ?? '');
  const [bestBefore, setBestBefore] = useState('');
  // Client-side mirror of the server's RETOUR -> QUALITY FAULT(103) default, so
  // the operator sees what will happen; still overridable, incl. back to None.
  const [lock, setLock] = useState(receiptType === GOODS_RECEIPT_TYPE.RETOUR ? '103' : '');
  const [note, setNote] = useState('');
  const [serial, setSerial] = useState('');
  // Plain packaging-unit id input — a product-scoped picker needs a product-detail
  // fetch and is deferred to the B4-full row.
  const [packaging, setPackaging] = useState('');
  const [errors, setErrors] = useState<FormErrors>({});
  /** Over-receipt confirm banner: amount that exceeds expectation. */
  const [overReceipt, setOverReceipt] = useState<{ exceedBy: number; expected: number } | null>(
    null,
  );

  // Re-seed the form whenever the prefill target changes (key forces remount;
  // this fallback also keeps lot/amount in sync if the parent reuses the node).
  const lockedProduct = prefill.asnLine;

  function validate(): boolean {
    const next: FormErrors = {};
    if (!lockedProduct && !product) next.product = 'Pick a product';
    if (!(Number(amount) > 0)) next.amount = 'Amount must be positive';
    if (!location) next.location = 'Pick a location';
    setErrors(next);
    return Object.keys(next).length === 0;
  }

  function submit(allowOverReceipt: boolean) {
    if (!validate() || !location) return;
    receiveLine.mutate(
      {
        id: receiptId,
        asnLineId: lockedProduct ? lockedProduct.id : undefined,
        itemDataId: lockedProduct ? undefined : product!.id,
        amount: Number(amount),
        locationId: location.id,
        locationName: location.name,
        unitLoadLabel: unitLoadLabel.trim() || undefined,
        lotNumber: lotNumber.trim() || undefined,
        bestBefore: bestBefore || undefined,
        lockType: lock === '' ? undefined : Number(lock),
        note: lock === '' ? undefined : note.trim() || undefined,
        serialNumber: serial.trim() || undefined,
        packagingUnitId: packaging === '' ? undefined : Number(packaging),
        allowOverReceipt,
      },
      {
        onSuccess: () => {
          setOverReceipt(null);
          toast.success('Line received');
          onReceived();
        },
        onError: (err) => {
          // 409 over-receipt -> inline confirm-and-retry (no toast double-up).
          if (err instanceof ApiError && err.problem.type.endsWith('over-receipt')) {
            toast.dismiss();
            const expected = lockedProduct?.expectedAmount ?? 0;
            const already = lockedProduct?.receivedAmount ?? 0;
            const exceedBy = already + Number(amount) - expected;
            setOverReceipt({ exceedBy, expected });
          }
        },
      },
    );
  }

  function handleSubmit() {
    submit(false);
  }

  return (
    <div className="space-y-4" data-testid="receive-form">
      {/* Product (locked when receiving against an ASN line) */}
      <div className="space-y-1.5">
        <Label htmlFor="receive-product">Product</Label>
        {lockedProduct ? (
          <div
            className="flex h-9 items-center gap-2 rounded-md border bg-muted/40 px-3 text-sm"
            data-testid="receive-locked-product"
          >
            <span className="font-mono text-[13px]">{lockedProduct.itemDataNumber}</span>
            <span className="text-muted-foreground">
              remaining {lockedProduct.remainingAmount.toFixed(2)}
            </span>
          </div>
        ) : (
          <ProductPicker inputId="receive-product" value={product} onChange={setProduct} />
        )}
        {errors.product && <p className="text-sm text-destructive">{errors.product}</p>}
      </div>

      {/* Amount + Location */}
      <div className="grid grid-cols-2 gap-3">
        <div className="space-y-1.5">
          <Label htmlFor="receive-amount">Amount</Label>
          <Input
            id="receive-amount"
            type="number"
            min="0"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !disabled) {
                e.preventDefault();
                handleSubmit();
              }
            }}
            placeholder="Qty"
            data-testid="receive-amount"
          />
          {errors.amount && <p className="text-sm text-destructive">{errors.amount}</p>}
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="receive-location">Location</Label>
          <LocationPicker inputId="receive-location" value={location} onChange={setLocation} />
          {errors.location && <p className="text-sm text-destructive">{errors.location}</p>}
        </div>
      </div>

      {/* Unit load label */}
      <div className="space-y-1.5">
        <Label htmlFor="receive-ul">Unit load label</Label>
        <Input
          id="receive-ul"
          value={unitLoadLabel}
          onChange={(e) => setUnitLoadLabel(e.target.value)}
          placeholder="Optional"
        />
        <p className="text-xs text-muted-foreground">Leave empty to create a new pallet.</p>
      </div>

      {/* Lot + Best before */}
      <div className="grid grid-cols-2 gap-3">
        <div className="space-y-1.5">
          <Label htmlFor="receive-lot">Lot</Label>
          <Input
            id="receive-lot"
            value={lotNumber}
            onChange={(e) => setLotNumber(e.target.value)}
            placeholder="Optional"
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="receive-bb">Best before</Label>
          <Input
            id="receive-bb"
            type="date"
            value={bestBefore}
            onChange={(e) => setBestBefore(e.target.value)}
          />
        </div>
      </div>

      {/* Lock type + note */}
      <div className="grid grid-cols-2 gap-3">
        <div className="space-y-1.5">
          <Label htmlFor="receive-lock">Lock</Label>
          <select
            id="receive-lock"
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs outline-none"
            value={lock}
            onChange={(e) => {
              const next = e.target.value;
              setLock(next);
              if (next === '') setNote('');
            }}
            data-testid="receive-lock-select"
          >
            <option value="">None</option>
            {Object.entries(RECEIPT_LOCK_LABELS).map(([code, label]) => (
              <option key={code} value={code}>
                {label}
              </option>
            ))}
          </select>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="receive-note">Note</Label>
          <Input
            id="receive-note"
            value={note}
            onChange={(e) => setNote(e.target.value)}
            maxLength={255}
            disabled={lock === ''}
            placeholder="Optional"
            data-testid="receive-note"
          />
        </div>
      </div>

      {/* Serial + Packaging unit */}
      <div className="grid grid-cols-2 gap-3">
        <div className="space-y-1.5">
          <Label htmlFor="receive-serial">Serial #</Label>
          <Input
            id="receive-serial"
            value={serial}
            onChange={(e) => setSerial(e.target.value)}
            placeholder="Optional"
            data-testid="receive-serial"
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="receive-packaging">Packaging unit</Label>
          <Input
            id="receive-packaging"
            type="number"
            min="0"
            value={packaging}
            onChange={(e) => setPackaging(e.target.value)}
            placeholder="Optional"
            data-testid="receive-packaging"
          />
        </div>
      </div>

      {/* Over-receipt inline confirm */}
      {overReceipt && (
        <div
          className="rounded-md border border-warning bg-warning/15 p-3 text-sm"
          data-testid="over-receipt-confirm"
        >
          <p className="font-medium text-warning-foreground">
            Expected {overReceipt.expected.toFixed(0)}, this exceeds by{' '}
            {overReceipt.exceedBy.toFixed(0)} — receive anyway?
          </p>
          <div className="mt-2 flex gap-2">
            <Button
              size="sm"
              variant="outline"
              onClick={() => setOverReceipt(null)}
              data-testid="over-receipt-cancel"
            >
              Cancel
            </Button>
            <Button
              size="sm"
              onClick={() => submit(true)}
              disabled={receiveLine.isPending}
              data-testid="over-receipt-confirm-button"
            >
              Receive anyway
            </Button>
          </div>
        </div>
      )}

      <Button
        onClick={handleSubmit}
        disabled={disabled || receiveLine.isPending}
        className="w-full"
        data-testid="receive-submit"
      >
        {receiveLine.isPending ? 'Receiving...' : 'Receive'}
      </Button>
    </div>
  );
}
