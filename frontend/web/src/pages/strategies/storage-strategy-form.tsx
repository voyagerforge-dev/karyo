import { useState } from 'react';
import { ArrowDown, ArrowUp, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Switch } from '@/components/ui/switch';
import type { StorageStrategyResponse } from '@/types/strategies';
import { useCreateStorageStrategy, useUpdateStorageStrategy } from './use-strategies';

// Mirrors backend `com.karyo.layout.vo.StorageStrategySortType` — the 9 typed sort
// dimensions (locations-layout sprint Task 5). The `sorts` CSV token IS the enum name.
const SORT_TYPES = [
  'CLIENT',
  'STORAGEAREA',
  'ZONE',
  'CAPACITY',
  'ALLOCATION',
  'POSITION_X',
  'POSITION_Y',
  'NAME',
  'ORDERINDEX',
] as const;
type SortType = (typeof SORT_TYPES)[number];

const SORT_TYPE_LABELS: Record<SortType, string> = {
  CLIENT: 'Client (owner first)',
  STORAGEAREA: 'Storage area order',
  ZONE: 'Zone flow (overflow chain)',
  CAPACITY: 'Capacity order',
  ALLOCATION: 'Allocation (fullest first)',
  POSITION_X: 'Position X',
  POSITION_Y: 'Position Y',
  NAME: 'Location name',
  ORDERINDEX: 'Location order index',
};

function isSortType(t: string): t is SortType {
  return (SORT_TYPES as readonly string[]).includes(t);
}

/** Unrecognized tokens (legacy free-text rows) are dropped silently — mirrors the
 * backend parser's read-time skip-unknown behavior, never a crash. */
function parseSorts(raw: string | null | undefined): SortType[] {
  if (!raw) return [];
  return raw
    .split(',')
    .map((t) => t.trim().toUpperCase())
    .filter(isSortType);
}

interface Props {
  strategy?: StorageStrategyResponse;
  onClose: () => void;
}

export function StorageStrategyForm({ strategy, onClose }: Props) {
  const isEdit = !!strategy;
  const createMutation = useCreateStorageStrategy();
  const updateMutation = useUpdateStorageStrategy();

  const [name, setName] = useState(strategy?.name ?? '');
  const [zoneId, setZoneId] = useState(strategy?.zoneId != null ? String(strategy.zoneId) : '');
  const [mixItem, setMixItem] = useState(strategy?.mixItem ?? true);
  const [mixClient, setMixClient] = useState(strategy?.mixClient ?? false);
  const [nearPicking, setNearPicking] = useState(strategy?.nearPickingLocation ?? false);
  const [sorts, setSorts] = useState<SortType[]>(() => parseSorts(strategy?.sorts));
  const [onlyClientLocation, setOnlyClientLocation] = useState(strategy?.onlyClientLocation ?? true);
  const [manualSearch, setManualSearch] = useState(strategy?.manualSearch ?? false);
  const [useAreaStrategyDate, setUseAreaStrategyDate] = useState(strategy?.useAreaStrategyDate ?? false);
  const [useItemDataArea, setUseItemDataArea] = useState(strategy?.useItemDataArea ?? false);
  const [nameError, setNameError] = useState<string | undefined>();

  const availableSortTypes = SORT_TYPES.filter((t) => !sorts.includes(t));

  function addSort(type: string) {
    if (!isSortType(type) || sorts.includes(type)) return;
    setSorts((prev) => [...prev, type]);
  }
  function removeSort(index: number) {
    setSorts((prev) => prev.filter((_, i) => i !== index));
  }
  function moveSort(index: number, direction: -1 | 1) {
    setSorts((prev) => {
      const target = index + direction;
      if (target < 0 || target >= prev.length) return prev;
      const next = [...prev];
      [next[index], next[target]] = [next[target], next[index]];
      return next;
    });
  }

  function handleSubmit() {
    if (!name.trim()) {
      setNameError('Name is required');
      return;
    }
    setNameError(undefined);
    const payload = {
      name: name.trim(),
      zoneId: zoneId ? Number(zoneId) : undefined,
      mixItem,
      mixClient,
      nearPickingLocation: nearPicking,
      sorts: sorts.length > 0 ? sorts.join(',') : undefined,
      onlyClientLocation,
      manualSearch,
      useAreaStrategyDate,
      useItemDataArea,
    };
    if (isEdit) {
      updateMutation.mutate({ id: strategy.id, ...payload }, { onSuccess: onClose });
    } else {
      createMutation.mutate(payload, { onSuccess: onClose });
    }
  }

  const isSubmitting = createMutation.isPending || updateMutation.isPending;

  return (
    <div className="space-y-6">
      <div className="space-y-2">
        <Label htmlFor="ss-name">Name</Label>
        <Input id="ss-name" value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. NEAR-PICK" />
        {nameError && <p className="text-sm text-destructive">{nameError}</p>}
      </div>

      <div className="space-y-2">
        <Label htmlFor="ss-zone">Zone ID (optional)</Label>
        <Input id="ss-zone" type="number" value={zoneId} onChange={(e) => setZoneId(e.target.value)} placeholder="Any zone" />
      </div>

      <div className="flex items-center justify-between">
        <Label htmlFor="ss-mixitem">Mix item</Label>
        <Switch id="ss-mixitem" checked={mixItem} onCheckedChange={setMixItem} />
      </div>
      <div className="flex items-center justify-between">
        <Label htmlFor="ss-mixclient">Mix client</Label>
        <Switch id="ss-mixclient" checked={mixClient} onCheckedChange={setMixClient} />
      </div>
      <div className="flex items-center justify-between">
        <Label htmlFor="ss-nearpick">Near picking location</Label>
        <Switch id="ss-nearpick" checked={nearPicking} onCheckedChange={setNearPicking} />
      </div>
      <div className="flex items-center justify-between">
        <Label htmlFor="ss-onlyclient">Only client location</Label>
        <Switch id="ss-onlyclient" checked={onlyClientLocation} onCheckedChange={setOnlyClientLocation} />
      </div>
      <div className="flex items-center justify-between">
        <Label htmlFor="ss-manualsearch">Manual search</Label>
        <Switch id="ss-manualsearch" checked={manualSearch} onCheckedChange={setManualSearch} />
      </div>
      <div className="flex items-center justify-between">
        <Label htmlFor="ss-areastrategydate">Use area strategy date</Label>
        <Switch id="ss-areastrategydate" checked={useAreaStrategyDate} onCheckedChange={setUseAreaStrategyDate} />
      </div>
      <div className="flex items-center justify-between">
        <Label htmlFor="ss-itemdataarea">Use item data area</Label>
        <Switch id="ss-itemdataarea" checked={useItemDataArea} onCheckedChange={setUseItemDataArea} />
      </div>

      <div className="space-y-2">
        <Label htmlFor="ss-sorts-add">Location sort order (optional)</Label>
        <p className="text-xs text-muted-foreground">
          Ranked candidate ordering, top = highest priority. Empty = today&apos;s default (emptiest location first).
        </p>
        {sorts.length > 0 && (
          <ul className="space-y-1" data-testid="storage-strategy-sorts-list">
            {sorts.map((type, index) => (
              <li
                key={type}
                data-testid={`storage-strategy-sort-chip-${type}`}
                className="flex items-center justify-between gap-2 rounded-md border border-border bg-muted/40 px-2 py-1"
              >
                <span className="text-sm">
                  {index + 1}. {SORT_TYPE_LABELS[type]}
                </span>
                <div className="flex items-center gap-1">
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    disabled={index === 0}
                    onClick={() => moveSort(index, -1)}
                    aria-label={`Move ${SORT_TYPE_LABELS[type]} up`}
                  >
                    <ArrowUp className="size-4" />
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    disabled={index === sorts.length - 1}
                    onClick={() => moveSort(index, 1)}
                    aria-label={`Move ${SORT_TYPE_LABELS[type]} down`}
                  >
                    <ArrowDown className="size-4" />
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    onClick={() => removeSort(index)}
                    aria-label={`Remove ${SORT_TYPE_LABELS[type]}`}
                  >
                    <Trash2 className="size-4" />
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        )}
        {availableSortTypes.length > 0 && (
          <Select value="" onValueChange={addSort}>
            <SelectTrigger id="ss-sorts-add" className="w-full">
              <SelectValue placeholder="Add a sort..." />
            </SelectTrigger>
            <SelectContent>
              {availableSortTypes.map((type) => (
                <SelectItem key={type} value={type}>
                  {SORT_TYPE_LABELS[type]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}
      </div>

      <div className="flex justify-end gap-2 border-t pt-4">
        <Button variant="outline" onClick={onClose}>Cancel</Button>
        <Button onClick={handleSubmit} disabled={isSubmitting} data-testid="storage-strategy-submit">
          {isSubmitting ? 'Saving...' : isEdit ? 'Save' : 'Create'}
        </Button>
      </div>
    </div>
  );
}
