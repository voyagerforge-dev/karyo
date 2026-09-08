import { useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Button } from '@/components/ui/button';
import { SectionCard } from '@/components/control/section-card';
import { ProductPicker, type PickedProduct } from '@/pages/orders/product-picker';
import { useCreateAsn, useUpdateAsn } from './use-asns';
import type { AsnResponse } from '@/types/receiving';

interface AsnFormProps {
  /** Existing ASN for header-edit mode (CREATED only); omit for create mode */
  asn?: AsnResponse;
  onClose: () => void;
}

interface LineDraft {
  key: number;
  product: PickedProduct | null;
  expectedAmount: string;
  lotNumber: string;
}

interface FormErrors {
  lines?: string;
}

let lineKeySeq = 0;
function newLine(): LineDraft {
  lineKeySeq += 1;
  return { key: lineKeySeq, product: null, expectedAmount: '', lotNumber: '' };
}

export function AsnForm({ asn, onClose }: AsnFormProps) {
  const isEdit = !!asn;
  const createMutation = useCreateAsn();
  const updateMutation = useUpdateAsn();

  const [asnNumber, setAsnNumber] = useState(asn?.asnNumber ?? '');
  const [externalNumber, setExternalNumber] = useState(asn?.externalNumber ?? '');
  const [carrierName, setCarrierName] = useState(asn?.carrierName ?? '');
  const [supplier, setSupplier] = useState(asn?.supplierName ?? '');
  const [sender, setSender] = useState(asn?.senderName ?? '');
  const [expectedDate, setExpectedDate] = useState(asn?.expectedDate ?? '');
  const [notes, setNotes] = useState(asn?.notes ?? '');
  const [lines, setLines] = useState<LineDraft[]>(() => [newLine()]);
  const [errors, setErrors] = useState<FormErrors>({});

  function setLine(key: number, patch: Partial<LineDraft>) {
    setLines((prev) => prev.map((l) => (l.key === key ? { ...l, ...patch } : l)));
  }

  function validate(): boolean {
    const next: FormErrors = {};
    if (!isEdit) {
      const valid = lines.filter((l) => l.product && Number(l.expectedAmount) > 0);
      if (valid.length === 0) {
        next.lines = 'At least one line with a product and a positive expected amount is required';
      } else if (lines.some((l) => l.product && !(Number(l.expectedAmount) > 0))) {
        next.lines = 'Every line needs a positive expected amount';
      } else if (lines.some((l) => !l.product && (l.expectedAmount || l.lotNumber))) {
        next.lines = 'Every line needs a product';
      }
    }
    setErrors(next);
    return Object.keys(next).length === 0;
  }

  function handleSubmit() {
    if (!validate()) return;

    if (isEdit) {
      updateMutation.mutate(
        {
          id: asn.id,
          externalNumber: externalNumber.trim() || undefined,
          carrierName: carrierName.trim() || undefined,
          supplierName: supplier.trim() || undefined,
          senderName: sender.trim() || undefined,
          expectedDate: expectedDate || undefined,
          notes: notes.trim() || undefined,
        },
        { onSuccess: () => onClose() },
      );
    } else {
      createMutation.mutate(
        {
          asnNumber: asnNumber.trim() || undefined,
          externalNumber: externalNumber.trim() || undefined,
          carrierName: carrierName.trim() || undefined,
          supplierName: supplier.trim() || undefined,
          senderName: sender.trim() || undefined,
          expectedDate: expectedDate || undefined,
          notes: notes.trim() || undefined,
          lines: lines
            .filter((l) => l.product)
            .map((l) => ({
              itemDataId: l.product!.id,
              expectedAmount: Number(l.expectedAmount),
              lotNumber: l.lotNumber.trim() || undefined,
            })),
        },
        { onSuccess: () => onClose() },
      );
    }
  }

  const isSubmitting = createMutation.isPending || updateMutation.isPending;

  return (
    <SectionCard title={isEdit ? `Edit ${asn.asnNumber}` : 'Create ASN'}>
      <div className="space-y-6">
        {/* ASN number (create only -- generated when left empty) */}
        {!isEdit && (
          <div className="space-y-2">
            <Label htmlFor="asn-number">ASN #</Label>
            <Input
              id="asn-number"
              value={asnNumber}
              onChange={(e) => setAsnNumber(e.target.value)}
              placeholder="Generated if empty"
            />
          </div>
        )}

        {/* External # + Carrier */}
        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label htmlFor="asn-external">External #</Label>
            <Input
              id="asn-external"
              value={externalNumber}
              onChange={(e) => setExternalNumber(e.target.value)}
              placeholder="ERP reference"
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="asn-carrier">Carrier</Label>
            <Input
              id="asn-carrier"
              value={carrierName}
              onChange={(e) => setCarrierName(e.target.value)}
              placeholder="Carrier name"
            />
          </div>
        </div>

        {/* Supplier + Sender -- accepted by the backend in CREATED state only;
          the form is only reachable while CREATED (edit is gated on it). */}
        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-2">
            <Label htmlFor="asn-supplier">Supplier</Label>
            <Input
              id="asn-supplier"
              value={supplier}
              onChange={(e) => setSupplier(e.target.value)}
              placeholder="Supplier name"
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="asn-sender">Sender</Label>
            <Input
              id="asn-sender"
              value={sender}
              onChange={(e) => setSender(e.target.value)}
              placeholder="Sender name"
            />
          </div>
        </div>

        {/* Expected date */}
        <div className="space-y-2">
          <Label htmlFor="asn-expected-date">Expected Date</Label>
          <Input
            id="asn-expected-date"
            type="date"
            value={expectedDate}
            onChange={(e) => setExpectedDate(e.target.value)}
          />
        </div>

        {/* Notes */}
        <div className="space-y-2">
          <Label htmlFor="asn-notes">Notes</Label>
          <Textarea
            id="asn-notes"
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            placeholder="Optional notes"
            rows={2}
          />
        </div>

        {/* Lines editor (create only -- line edits are not supported in v1.2) */}
        {!isEdit ? (
          <div className="space-y-3">
            <h3 className="text-sm font-medium">Expected Lines</h3>
            <div className="space-y-3">
              {lines.map((line, index) => (
                <div
                  key={line.key}
                  className="grid grid-cols-[1fr_90px_110px_32px] items-start gap-2"
                  data-testid={`asn-line-${index}`}
                >
                  <div className="space-y-1">
                    {index === 0 && <Label htmlFor={`asn-line-product-${line.key}`}>Product</Label>}
                    <ProductPicker
                      inputId={`asn-line-product-${line.key}`}
                      value={line.product}
                      onChange={(product) => setLine(line.key, { product })}
                    />
                  </div>
                  <div className="space-y-1">
                    {index === 0 && <Label htmlFor={`asn-line-amount-${line.key}`}>Expected</Label>}
                    <Input
                      id={`asn-line-amount-${line.key}`}
                      aria-label="Expected"
                      type="number"
                      min="0"
                      value={line.expectedAmount}
                      onChange={(e) => setLine(line.key, { expectedAmount: e.target.value })}
                      placeholder="Qty"
                    />
                  </div>
                  <div className="space-y-1">
                    {index === 0 && <Label htmlFor={`asn-line-lot-${line.key}`}>Lot</Label>}
                    <Input
                      id={`asn-line-lot-${line.key}`}
                      aria-label="Lot"
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
            Lines cannot be edited after creation — cancel the ASN and recreate it to change lines.
          </p>
        )}

        {/* Submit */}
        <div className="flex justify-end gap-2 border-t pt-4">
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={handleSubmit} disabled={isSubmitting} data-testid="asn-form-submit">
            {isSubmitting ? 'Saving...' : isEdit ? 'Save' : 'Create ASN'}
          </Button>
        </div>
      </div>
    </SectionCard>
  );
}
