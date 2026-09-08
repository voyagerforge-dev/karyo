import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select';

interface ManifestFormProps {
  isPending?: boolean;
  onConfirm: (carrierName: string, carrierService: string, trackingNumber: string | undefined) => void;
  onCancel: () => void;
}

const CARRIERS = ['MANUAL', 'UPS', 'FEDEX', 'DHL', 'USPS'] as const;

export function ManifestForm({ isPending, onConfirm, onCancel }: ManifestFormProps) {
  const [carrier, setCarrier] = useState<string>('MANUAL');
  const [service, setService] = useState('');
  const [tracking, setTracking] = useState('');
  const [error, setError] = useState<string | undefined>();

  function submit() {
    if (!service.trim()) {
      setError('Service is required');
      return;
    }
    setError(undefined);
    onConfirm(carrier, service.trim(), tracking.trim() || undefined);
  }

  return (
    <div className="space-y-3 rounded-md border bg-muted/30 p-3" data-testid="manifest-form">
      <div className="grid grid-cols-3 gap-3">
        <div className="space-y-1">
          <Label htmlFor="manifest-carrier">Carrier</Label>
          <Select value={carrier} onValueChange={setCarrier}>
            <SelectTrigger id="manifest-carrier"><SelectValue /></SelectTrigger>
            <SelectContent>
              {CARRIERS.map((c) => <SelectItem key={c} value={c}>{c}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-1">
          <Label htmlFor="manifest-service">Service</Label>
          <Input
            id="manifest-service"
            value={service}
            placeholder="GROUND"
            onChange={(e) => setService(e.target.value)}
          />
        </div>
        <div className="space-y-1">
          <Label htmlFor="manifest-tracking">Tracking (optional)</Label>
          <Input
            id="manifest-tracking"
            value={tracking}
            placeholder="auto"
            onChange={(e) => setTracking(e.target.value)}
          />
        </div>
      </div>
      {error && <p className="text-sm text-destructive">{error}</p>}
      <div className="flex justify-end gap-2">
        <Button variant="outline" size="sm" onClick={onCancel} disabled={isPending}>
          Cancel
        </Button>
        <Button size="sm" onClick={submit} disabled={isPending} data-testid="manifest-confirm-btn">
          {isPending ? 'Manifesting…' : 'Confirm manifest'}
        </Button>
      </div>
    </div>
  );
}
