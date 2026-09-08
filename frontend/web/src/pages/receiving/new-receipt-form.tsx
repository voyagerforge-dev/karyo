import { useState } from 'react';
import { useNavigate } from 'react-router';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Button } from '@/components/ui/button';
import { GOODS_RECEIPT_TYPE } from '@/types/receiving';
import { AsnPicker, type PickedAsn } from './asn-picker';
import { LocationPicker, type PickedLocation } from './location-picker';
import { useCreateGoodsReceipt } from './use-receiving';

interface NewReceiptFormProps {
  onClose: () => void;
}

/**
 * Opens a goods receipt and jumps to its workbench. The ASN binding is optional
 * (blind receipt when omitted) and, per V424, can bind any number of RELEASED/
 * STARTED ASNs via the multi-select picker. RETOUR receipts cannot bind an ASN
 * (backend 422 retour-with-asn) — selecting RETOUR clears + disables the ASN
 * picker so the UI never lets the user hit it.
 */
export function NewReceiptForm({ onClose }: NewReceiptFormProps) {
  const navigate = useNavigate();
  const createReceipt = useCreateGoodsReceipt();

  const [type, setType] = useState<number>(GOODS_RECEIPT_TYPE.NORMAL);
  const [asns, setAsns] = useState<PickedAsn[]>([]);
  const [carrierName, setCarrierName] = useState('');
  const [deliveryNoteNumber, setDeliveryNoteNumber] = useState('');
  const [notes, setNotes] = useState('');
  const [prio, setPrio] = useState('');
  const [receiptDate, setReceiptDate] = useState('');
  const [dock, setDock] = useState<PickedLocation | null>(null);

  function handleTypeChange(next: number) {
    setType(next);
    if (next === GOODS_RECEIPT_TYPE.RETOUR) {
      setAsns([]);
    }
  }

  function handleSubmit() {
    createReceipt.mutate(
      {
        asnIds: asns.map((a) => a.id),
        carrierName: carrierName.trim() || undefined,
        deliveryNoteNumber: deliveryNoteNumber.trim() || undefined,
        notes: notes.trim() || undefined,
        receiptType: type,
        prio: prio === '' ? undefined : Number(prio),
        receiptDate: receiptDate || undefined,
        dockLocationId: dock?.id,
        dockLocationName: dock?.name,
      },
      {
        onSuccess: (receipt) => {
          onClose();
          navigate(`/receiving/${receipt.id}`);
        },
      },
    );
  }

  const isRetour = type === GOODS_RECEIPT_TYPE.RETOUR;

  return (
    <div className="space-y-6">
      <div className="space-y-2">
        <Label>Type</Label>
        <div className="flex gap-2">
          <Button
            type="button"
            variant={type === GOODS_RECEIPT_TYPE.NORMAL ? 'default' : 'outline'}
            size="sm"
            data-testid="receipt-type-normal"
            onClick={() => handleTypeChange(GOODS_RECEIPT_TYPE.NORMAL)}
          >
            Normal
          </Button>
          <Button
            type="button"
            variant={isRetour ? 'default' : 'outline'}
            size="sm"
            data-testid="receipt-type-retour"
            onClick={() => handleTypeChange(GOODS_RECEIPT_TYPE.RETOUR)}
          >
            Retour
          </Button>
        </div>
      </div>

      <div className="space-y-2">
        <Label htmlFor="receipt-asn">ASNs</Label>
        {isRetour ? (
          <Input id="receipt-asn" disabled placeholder="Not applicable for RETOUR receipts" />
        ) : (
          <AsnPicker inputId="receipt-asn" value={asns} onChange={setAsns} />
        )}
        <p className="text-xs text-muted-foreground">
          {isRetour
            ? 'Customer returns arrive blind — RETOUR receipts cannot bind an ASN.'
            : 'Bind any number of released ASNs to receive against expectations, or leave empty for a blind receipt.'}
        </p>
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div className="space-y-2">
          <Label htmlFor="receipt-carrier">Carrier</Label>
          <Input
            id="receipt-carrier"
            value={carrierName}
            onChange={(e) => setCarrierName(e.target.value)}
            placeholder="Carrier name"
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="receipt-delivery-note">Delivery Note #</Label>
          <Input
            id="receipt-delivery-note"
            value={deliveryNoteNumber}
            onChange={(e) => setDeliveryNoteNumber(e.target.value)}
            placeholder="Optional"
          />
        </div>
      </div>

      <div className="grid grid-cols-3 gap-4">
        <div className="space-y-2">
          <Label htmlFor="receipt-prio">Prio</Label>
          <Input
            id="receipt-prio"
            type="number"
            value={prio}
            onChange={(e) => setPrio(e.target.value)}
            placeholder="50 (default)"
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="receipt-date">Receipt Date</Label>
          <Input
            id="receipt-date"
            type="date"
            value={receiptDate}
            onChange={(e) => setReceiptDate(e.target.value)}
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="receipt-dock">Dock</Label>
          <LocationPicker inputId="receipt-dock" value={dock} onChange={setDock} />
        </div>
      </div>

      <div className="space-y-2">
        <Label htmlFor="receipt-notes">Notes</Label>
        <Textarea
          id="receipt-notes"
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          placeholder="Optional notes"
          rows={2}
        />
      </div>

      <div className="flex justify-end gap-2 border-t pt-4">
        <Button variant="outline" onClick={onClose}>
          Cancel
        </Button>
        <Button
          onClick={handleSubmit}
          disabled={createReceipt.isPending}
          data-testid="new-receipt-submit"
        >
          {createReceipt.isPending ? 'Opening...' : 'Open Receipt'}
        </Button>
      </div>
    </div>
  );
}
