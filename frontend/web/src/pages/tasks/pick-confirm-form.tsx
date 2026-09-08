import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';

interface PickConfirmFormProps {
  plannedAmount: number;
  isPending?: boolean;
  onConfirm: (pickedAmount: number, targetUnitLoadId: number | undefined) => void;
  onCancel: () => void;
}

export function PickConfirmForm({
  plannedAmount,
  isPending,
  onConfirm,
  onCancel,
}: PickConfirmFormProps) {
  const [qty, setQty] = useState(String(plannedAmount));
  const [targetUl, setTargetUl] = useState('');
  const [error, setError] = useState<string | undefined>();

  function submit() {
    const amount = Number(qty);
    if (!qty || Number.isNaN(amount) || amount <= 0 || amount > plannedAmount) {
      setError(`Picked qty must be between 0 and ${plannedAmount}`);
      return;
    }
    const trimmed = targetUl.trim();
    const ulId = trimmed ? Number(trimmed) : undefined;
    if (trimmed && (ulId === undefined || Number.isNaN(ulId) || ulId <= 0)) {
      setError('Target unit load must be a valid id');
      return;
    }
    setError(undefined);
    onConfirm(amount, ulId);
  }

  return (
    <div
      className="space-y-3 rounded-md border bg-muted/30 p-3"
      data-testid="pick-confirm-form"
    >
      <div className="grid grid-cols-2 gap-3">
        <div className="space-y-1">
          <Label htmlFor="pick-qty">Picked qty</Label>
          <Input
            id="pick-qty"
            type="number"
            value={qty}
            onChange={(e) => setQty(e.target.value)}
          />
        </div>
        <div className="space-y-1">
          <Label htmlFor="pick-target-ul">Target UL (optional)</Label>
          <Input
            id="pick-target-ul"
            type="number"
            value={targetUl}
            placeholder="pick container"
            onChange={(e) => setTargetUl(e.target.value)}
          />
        </div>
      </div>
      {error && <p className="text-sm text-destructive">{error}</p>}
      <div className="flex justify-end gap-2">
        <Button variant="outline" size="sm" onClick={onCancel} disabled={isPending}>
          Cancel
        </Button>
        <Button size="sm" onClick={submit} disabled={isPending}>
          {isPending ? 'Confirming…' : 'Confirm pick'}
        </Button>
      </div>
    </div>
  );
}
