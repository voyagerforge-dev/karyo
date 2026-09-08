import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select';

interface PackFormProps {
  isPending?: boolean;
  onConfirm: (weight: number, type: string) => void;
  onCancel: () => void;
}

const CARTON_TYPES = ['CARTON', 'PALLET'] as const;

export function PackForm({ isPending, onConfirm, onCancel }: PackFormProps) {
  const [weight, setWeight] = useState('');
  const [type, setType] = useState<string>('CARTON');
  const [error, setError] = useState<string | undefined>();

  function submit() {
    const w = Number(weight);
    if (!weight || Number.isNaN(w) || w <= 0) {
      setError('Weight must be greater than 0');
      return;
    }
    setError(undefined);
    onConfirm(w, type);
  }

  return (
    <div className="space-y-3 rounded-md border bg-muted/30 p-3" data-testid="pack-form">
      <div className="grid grid-cols-2 gap-3">
        <div className="space-y-1">
          <Label htmlFor="pack-weight">Weight (kg)</Label>
          <Input
            id="pack-weight"
            type="number"
            value={weight}
            placeholder="0.0"
            onChange={(e) => setWeight(e.target.value)}
          />
          <p className="text-xs text-muted-foreground">
            Weight of what is being packed in this call, not the shipment total.
          </p>
        </div>
        <div className="space-y-1">
          <Label htmlFor="pack-type">Carton type</Label>
          <Select value={type} onValueChange={setType}>
            <SelectTrigger id="pack-type">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {CARTON_TYPES.map((t) => (
                <SelectItem key={t} value={t}>{t}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </div>
      {error && <p className="text-sm text-destructive">{error}</p>}
      <div className="flex justify-end gap-2">
        <Button variant="outline" size="sm" onClick={onCancel} disabled={isPending}>
          Cancel
        </Button>
        <Button size="sm" onClick={submit} disabled={isPending} data-testid="pack-confirm-btn">
          {isPending ? 'Packing…' : 'Confirm pack'}
        </Button>
      </div>
    </div>
  );
}
