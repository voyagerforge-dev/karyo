import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Label } from '@/components/ui/label';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { api } from '@/lib/api-client';
import { LocationPicker, type PickedLocation } from '@/pages/receiving/location-picker';
import { useCreateManualMove } from './use-tasks';
import type { UnitLoadResponse } from '@/types/inventory';

interface ManualMoveFormProps {
  onClose: () => void;
}

interface FormErrors {
  source?: string;
  unitLoad?: string;
  destination?: string;
}

/**
 * Manual MOVE form. The unit-loads list endpoint is scoped by location, so the
 * operator first picks a SOURCE location, the form lists the unit loads parked
 * there, they pick one, then choose the DESTINATION. Creates a MOVE task.
 */
export function ManualMoveForm({ onClose }: ManualMoveFormProps) {
  const createMove = useCreateManualMove();

  const [source, setSource] = useState<PickedLocation | null>(null);
  const [unitLoadId, setUnitLoadId] = useState<number | null>(null);
  const [destination, setDestination] = useState<PickedLocation | null>(null);
  const [externalNumber, setExternalNumber] = useState('');
  const [externalId, setExternalId] = useState('');
  const [errors, setErrors] = useState<FormErrors>({});

  // Unit loads currently parked at the chosen source location.
  const { data: unitLoads, isLoading: ulLoading } = useQuery({
    queryKey: ['unit-loads', 'by-location', source?.id],
    queryFn: () =>
      api.get<UnitLoadResponse[]>(`/api/v1/unit-loads?locationId=${source!.id}`),
    enabled: source != null,
    staleTime: 5_000,
  });

  const ulOptions = useMemo(() => unitLoads ?? [], [unitLoads]);

  function validate(): boolean {
    const next: FormErrors = {};
    if (!source) next.source = 'Pick a source location';
    if (!unitLoadId) next.unitLoad = 'Pick a unit load';
    if (!destination) next.destination = 'Pick a destination';
    setErrors(next);
    return Object.keys(next).length === 0;
  }

  function handleSubmit() {
    if (!validate() || !destination || !unitLoadId) return;
    createMove.mutate(
      {
        unitLoadId,
        destinationLocationId: destination.id,
        destinationLocationName: destination.name,
        ...(externalNumber.trim() ? { externalNumber: externalNumber.trim() } : {}),
        ...(externalId.trim() ? { externalId: externalId.trim() } : {}),
      },
      {
        onSuccess: () => {
          toast.dismiss();
          onClose();
        },
      },
    );
  }

  return (
    <div className="space-y-4" data-testid="manual-move-form">
      {/* Source location */}
      <div className="space-y-1.5">
        <Label htmlFor="move-source">Source location</Label>
        <LocationPicker
          inputId="move-source"
          value={source}
          onChange={(loc) => {
            setSource(loc);
            setUnitLoadId(null);
          }}
        />
        {errors.source && <p className="text-sm text-destructive">{errors.source}</p>}
      </div>

      {/* Unit load (scoped to the source location) */}
      <div className="space-y-1.5">
        <Label htmlFor="move-unit-load">Unit load</Label>
        {!source ? (
          <p className="text-xs text-muted-foreground">Pick a source location first.</p>
        ) : ulLoading ? (
          <p className="text-xs text-muted-foreground">Loading unit loads…</p>
        ) : ulOptions.length === 0 ? (
          <p className="text-xs text-muted-foreground" data-testid="move-no-unit-loads">
            No unit loads at this location.
          </p>
        ) : (
          <select
            id="move-unit-load"
            data-testid="move-unit-load-select"
            className="flex h-9 w-full rounded-md border bg-transparent px-3 py-1 text-sm shadow-sm"
            value={unitLoadId ?? ''}
            onChange={(e) => setUnitLoadId(e.target.value ? Number(e.target.value) : null)}
          >
            <option value="">Select a unit load…</option>
            {ulOptions.map((ul) => (
              <option key={ul.id} value={ul.id}>
                {ul.labelId}
              </option>
            ))}
          </select>
        )}
        {errors.unitLoad && <p className="text-sm text-destructive">{errors.unitLoad}</p>}
      </div>

      {/* Destination location */}
      <div className="space-y-1.5">
        <Label htmlFor="move-destination">Destination location</Label>
        <LocationPicker
          inputId="move-destination"
          value={destination}
          onChange={setDestination}
        />
        {errors.destination && (
          <p className="text-sm text-destructive">{errors.destination}</p>
        )}
      </div>

      {/* PT17: optional ERP reference pair — threaded only through this manual-move path. */}
      <div className="grid grid-cols-2 gap-4">
        <div className="space-y-1.5">
          <Label htmlFor="move-external-number">External ref (optional)</Label>
          <Input
            id="move-external-number"
            data-testid="move-external-number-input"
            value={externalNumber}
            onChange={(e) => setExternalNumber(e.target.value)}
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="move-external-id">External ID (optional)</Label>
          <Input
            id="move-external-id"
            data-testid="move-external-id-input"
            value={externalId}
            onChange={(e) => setExternalId(e.target.value)}
          />
        </div>
      </div>

      <Button
        onClick={handleSubmit}
        disabled={createMove.isPending}
        className="w-full"
        data-testid="manual-move-submit"
      >
        {createMove.isPending ? 'Creating…' : 'Create move'}
      </Button>
    </div>
  );
}
