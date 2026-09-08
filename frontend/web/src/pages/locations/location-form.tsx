import { useState, useCallback } from 'react';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Checkbox } from '@/components/ui/checkbox';
import { Switch } from '@/components/ui/switch';
import { Button } from '@/components/ui/button';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { toast } from 'sonner';
import { ApiError } from '@/lib/api-client';
import {
  useCreateZone,
  useCreateArea,
  useCreateCluster,
  useCreateLocation,
  useUpdateLocation,
  useAllZones,
  useAllClusters,
  useLocationTypes,
} from './use-locations';
import type {
  ZoneResponse,
  AreaResponse,
  LocationClusterResponse,
  LocationResponse,
  CreateZoneRequest,
  CreateAreaRequest,
  CreateLocationRequest,
  CreateLocationClusterRequest,
} from '@/types/location';

/** The four entity tiers this adaptive form can create/edit. */
export type HierarchyLevel = 'zones' | 'areas' | 'clusters' | 'locations';

interface LocationFormProps {
  level: HierarchyLevel;
  entity?: ZoneResponse | AreaResponse | LocationClusterResponse | LocationResponse | null;
  parentId?: number;
  onSave: () => void;
  onCancel: () => void;
}

const USAGE_OPTIONS = ['RECEIVING', 'STORAGE', 'PICKING', 'SHIPPING', 'CROSS_DOCK'];

// Mirrors location-derive.ts's kindFromBackend map + the demo seeder's real
// ZONE_TEMPERATURE/handlingClassFor value sets (CatalogGenerator.kt) — every
// option here is a value the backend actually produces, none invented.
const LOCATION_KINDS = ['PICK_FACE', 'RESERVE', 'STAGING', 'BULK'];
const TEMPERATURE_ZONES = ['AMBIENT', 'CHILLED', 'FROZEN'];
const HANDLING_CLASSES = ['STANDARD', 'HAZMAT', 'FRAGILE', 'HIGH_VALUE'];

/**
 * Adaptive form for creating/editing zone, area, cluster, or location entities.
 * Fields change based on the hierarchy level.
 */
export function LocationForm({ level, entity, parentId, onSave, onCancel }: LocationFormProps) {
  const isEditing = entity !== null && entity !== undefined;

  // ---------- Form state ----------
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [usages, setUsages] = useState<string[]>([]);
  const [scanCode, setScanCode] = useState('');
  const [locationTypeId, setLocationTypeId] = useState('');
  const [orderIndex, setOrderIndex] = useState('');
  const [rack, setRack] = useState('');
  const [fieldVal, setFieldVal] = useState('');
  const [section, setSection] = useState('');
  const [zoneId, setZoneId] = useState('');
  const [locationClusterId, setLocationClusterId] = useState('');
  const [xPos, setXPos] = useState('');
  const [yPos, setYPos] = useState('');
  const [zPos, setZPos] = useState('');
  const [overflowZoneId, setOverflowZoneId] = useState('');
  const [parentClusterId, setParentClusterId] = useState('');
  // Task 10: orphaned V309/V310 fields -- capacity/temperatureZone/handlingClass/kind
  // were response-only until this task; isClearing/plcCode/allocationState already had
  // wire support but no form UI.
  const [capacity, setCapacity] = useState('');
  const [temperatureZone, setTemperatureZone] = useState('');
  const [handlingClass, setHandlingClass] = useState('');
  const [kind, setKind] = useState('');
  const [isClearing, setIsClearing] = useState(false);
  const [plcCode, setPlcCode] = useState('');
  const [excludedFromPutaway, setExcludedFromPutaway] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});

  // ---------- Lookup queries ----------
  const { data: zonesData } = useAllZones();
  const { data: clustersData } = useAllClusters();
  const { data: locationTypesData, isLoading: locationTypesLoading } = useLocationTypes({ page: 0, size: 100 });

  // ---------- Mutations ----------
  const createZone = useCreateZone();
  const createArea = useCreateArea();
  const createCluster = useCreateCluster();
  const createLocation = useCreateLocation();
  const updateLocation = useUpdateLocation();

  // ---------- Pre-fill for edit mode ----------
  // Adjust state when the `entity` prop changes (React's "you might not need an
  // effect" pattern — set state during render, keyed on entity identity, rather
  // than firing a synchronous setState inside an effect).
  const entityKey = entity ? `${level}:${'id' in entity ? entity.id : 'new'}` : null;
  const [prefilledKey, setPrefilledKey] = useState<string | null>(null);
  if (entity && entityKey !== prefilledKey) {
    setPrefilledKey(entityKey);
    setName(entity.name);
    if (level === 'zones') {
      const zone = entity as ZoneResponse;
      setDescription(zone.description ?? '');
      setOverflowZoneId(zone.overflowZoneId != null ? String(zone.overflowZoneId) : '');
    } else if (level === 'areas') {
      const area = entity as AreaResponse;
      setUsages(area.usages ?? []);
    } else if (level === 'locations') {
      const loc = entity as LocationResponse;
      setScanCode(loc.scanCode ?? '');
      setLocationTypeId(String(loc.locationType.id));
      setOrderIndex(String(loc.orderIndex));
      setRack(loc.rack ?? '');
      setFieldVal(loc.field ?? '');
      setSection(loc.section ?? '');
      setZoneId(loc.zone?.id != null ? String(loc.zone.id) : '');
      setLocationClusterId(loc.locationCluster?.id != null ? String(loc.locationCluster.id) : '');
      setXPos(String(loc.xPos));
      setYPos(String(loc.yPos));
      setZPos(String(loc.zPos));
      setCapacity(loc.capacity != null ? String(loc.capacity) : '');
      setTemperatureZone(loc.temperatureZone ?? '');
      setHandlingClass(loc.handlingClass ?? '');
      setKind(loc.kind ?? '');
      setIsClearing(loc.isClearing);
      setPlcCode(loc.plcCode ?? '');
      setExcludedFromPutaway(loc.allocationState !== 0);
    }
  }

  // ---------- Validation ----------
  const validate = useCallback((): boolean => {
    const newErrors: Record<string, string> = {};

    if (!name.trim()) {
      newErrors.name = 'Name is required';
    }

    if (level === 'locations' && !locationTypeId) {
      newErrors.locationTypeId = 'Location type is required';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  }, [name, level, locationTypeId]);

  // ---------- Submit ----------
  const handleSubmit = useCallback(async () => {
    if (!validate()) return;

    try {
      switch (level) {
        case 'zones': {
          const data: CreateZoneRequest = { name: name.trim() };
          if (description.trim()) data.description = description.trim();
          if (overflowZoneId && overflowZoneId !== 'none') data.overflowZoneId = Number(overflowZoneId);
          if (isEditing) {
            // Zones don't have a dedicated update endpoint in Plan 03 scope
            // For now, only create is supported
          } else {
            await createZone.mutateAsync(data);
          }
          break;
        }
        case 'areas': {
          const data: CreateAreaRequest = { name: name.trim() };
          if (usages.length > 0) data.usages = usages;
          await createArea.mutateAsync(data);
          break;
        }
        case 'clusters': {
          if (isEditing) {
            // No cluster update endpoint in current scope
            break;
          }
          const data: CreateLocationClusterRequest = { name: name.trim() };
          if (parentClusterId && parentClusterId !== 'none') data.parentClusterId = Number(parentClusterId);
          await createCluster.mutateAsync(data);
          break;
        }
        case 'locations': {
          const data: CreateLocationRequest = {
            name: name.trim(),
            locationTypeId: Number(locationTypeId),
            areaId: parentId ?? 0,
          };
          if (scanCode.trim()) data.scanCode = scanCode.trim();
          if (orderIndex) data.orderIndex = Number(orderIndex);
          if (rack.trim()) data.rack = rack.trim();
          if (fieldVal.trim()) data.field = fieldVal.trim();
          if (section.trim()) data.section = section.trim();
          if (zoneId && zoneId !== 'none') data.zoneId = Number(zoneId);
          if (locationClusterId && locationClusterId !== 'none') data.locationClusterId = Number(locationClusterId);
          if (xPos) data.xPos = Number(xPos);
          if (yPos) data.yPos = Number(yPos);
          if (zPos) data.zPos = Number(zPos);
          if (capacity) data.capacity = Number(capacity);
          if (temperatureZone && temperatureZone !== 'none') data.temperatureZone = temperatureZone;
          if (handlingClass && handlingClass !== 'none') data.handlingClass = handlingClass;
          if (kind && kind !== 'none') data.kind = kind;
          if (plcCode.trim()) data.plcCode = plcCode.trim();
          // Switches map directly to intent (unlike the optional text/number fields above,
          // "off" is a meaningful value here, not "leave unset") -- always send both.
          data.isClearing = isClearing;
          data.allocationState = excludedFromPutaway ? 1 : 0;

          if (isEditing) {
            const loc = entity as LocationResponse;
            await updateLocation.mutateAsync({ id: loc.id, data });
          } else {
            await createLocation.mutateAsync(data);
          }
          break;
        }
      }
      onSave();
    } catch (err) {
      // A2-1's clearing-location singleton (V310) surfaces as a clean 409 -- show it
      // inline next to the switch instead of leaving the caller to guess from the toast.
      if (err instanceof ApiError && err.problem.type.endsWith('location-in-use')) {
        toast.dismiss();
        setErrors((prev) => ({
          ...prev,
          isClearing: err.problem.detail || 'Another location is already the clearing location.',
        }));
      }
      // Other API errors are handled by api-client's toast.
    }
  }, [
    validate, level, name, description, usages, scanCode, locationTypeId,
    orderIndex, rack, fieldVal, section, parentId, isEditing, entity,
    zoneId, locationClusterId, xPos, yPos, zPos, overflowZoneId, parentClusterId,
    capacity, temperatureZone, handlingClass, kind, isClearing, plcCode, excludedFromPutaway,
    createZone, createArea, createCluster, createLocation, updateLocation, onSave,
  ]);

  const isPending =
    createZone.isPending || createArea.isPending || createCluster.isPending ||
    createLocation.isPending || updateLocation.isPending;

  // ---------- Usage toggle ----------
  const toggleUsage = useCallback((usage: string) => {
    setUsages((prev) =>
      prev.includes(usage) ? prev.filter((u) => u !== usage) : [...prev, usage],
    );
  }, []);

  const zones = zonesData?.content ?? [];
  const clusters = clustersData?.content ?? [];
  const locationTypes = locationTypesData?.content ?? [];

  return (
    <div className="space-y-4">
      {/* Name field (all levels) */}
      <div className="space-y-2">
        <Label htmlFor="name">Name</Label>
        <Input
          id="name"
          value={name}
          onChange={(e) => {
            setName(e.target.value);
            if (errors.name) setErrors((prev) => ({ ...prev, name: '' }));
          }}
          placeholder="Enter name"
          aria-invalid={!!errors.name}
        />
        {errors.name && (
          <p className="text-sm text-destructive">{errors.name}</p>
        )}
      </div>

      {/* Zone-specific: Description + Overflow Zone */}
      {level === 'zones' && (
        <>
          <div className="space-y-2">
            <Label htmlFor="description">Description</Label>
            <Textarea
              id="description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Optional description"
            />
          </div>

          <div className="space-y-2">
            <Label>Overflow Zone</Label>
            <Select
              value={overflowZoneId}
              onValueChange={setOverflowZoneId}
            >
              <SelectTrigger>
                <SelectValue placeholder="None" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="none">None</SelectItem>
                {zones.map((z) => (
                  <SelectItem key={z.id} value={String(z.id)}>
                    {z.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </>
      )}

      {/* Area-specific: Usages */}
      {level === 'areas' && (
        <div className="space-y-2">
          <Label>Usages</Label>
          <div className="grid grid-cols-2 gap-2">
            {USAGE_OPTIONS.map((usage) => (
              <label key={usage} className="flex items-center gap-2 text-sm">
                <Checkbox
                  checked={usages.includes(usage)}
                  onCheckedChange={() => toggleUsage(usage)}
                />
                {usage}
              </label>
            ))}
          </div>
        </div>
      )}

      {/* Cluster-specific: Parent Cluster */}
      {level === 'clusters' && (
        <div className="space-y-2">
          <Label>Parent Cluster</Label>
          <Select
            value={parentClusterId}
            onValueChange={setParentClusterId}
          >
            <SelectTrigger>
              <SelectValue placeholder="None" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="none">None</SelectItem>
              {clusters.map((c) => (
                <SelectItem key={c.id} value={String(c.id)}>
                  {c.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      )}

      {/* Location-specific fields */}
      {level === 'locations' && (
        <>
          <div className="space-y-2">
            <Label htmlFor="scanCode">Scan Code</Label>
            <Input
              id="scanCode"
              value={scanCode}
              onChange={(e) => setScanCode(e.target.value)}
              placeholder="Optional scan code"
            />
          </div>

          <div className="space-y-2">
            <Label>Location Type</Label>
            <Select
              value={locationTypeId}
              onValueChange={(value) => {
                setLocationTypeId(value);
                if (errors.locationTypeId) setErrors((prev) => ({ ...prev, locationTypeId: '' }));
              }}
            >
              <SelectTrigger aria-invalid={!!errors.locationTypeId}>
                <SelectValue placeholder={locationTypesLoading ? 'Loading...' : 'Select location type'} />
              </SelectTrigger>
              <SelectContent>
                {locationTypes.map((lt) => (
                  <SelectItem key={lt.id} value={String(lt.id)}>
                    {lt.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {errors.locationTypeId && (
              <p className="text-sm text-destructive">{errors.locationTypeId}</p>
            )}
          </div>

          <div className="space-y-2">
            <Label htmlFor="orderIndex">Order Index</Label>
            <Input
              id="orderIndex"
              type="number"
              value={orderIndex}
              onChange={(e) => setOrderIndex(e.target.value)}
              placeholder="Sort order"
            />
          </div>

          <div className="grid grid-cols-3 gap-4">
            <div className="space-y-2">
              <Label htmlFor="rack">Rack</Label>
              <Input
                id="rack"
                value={rack}
                onChange={(e) => setRack(e.target.value)}
                placeholder="Rack"
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="field">Field</Label>
              <Input
                id="field"
                value={fieldVal}
                onChange={(e) => setFieldVal(e.target.value)}
                placeholder="Field"
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="section">Section</Label>
              <Input
                id="section"
                value={section}
                onChange={(e) => setSection(e.target.value)}
                placeholder="Section"
              />
            </div>
          </div>

          {/* Zone dropdown */}
          <div className="space-y-2">
            <Label>Zone</Label>
            <Select value={zoneId} onValueChange={setZoneId}>
              <SelectTrigger>
                <SelectValue placeholder="None" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="none">None</SelectItem>
                {zones.map((z) => (
                  <SelectItem key={z.id} value={String(z.id)}>
                    {z.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {/* Cluster dropdown */}
          <div className="space-y-2">
            <Label>Cluster</Label>
            <Select value={locationClusterId} onValueChange={setLocationClusterId}>
              <SelectTrigger>
                <SelectValue placeholder="None" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="none">None</SelectItem>
                {clusters.map((c) => (
                  <SelectItem key={c.id} value={String(c.id)}>
                    {c.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {/* Position row */}
          <div className="grid grid-cols-3 gap-4">
            <div className="space-y-2">
              <Label htmlFor="xPos">X Position</Label>
              <Input
                id="xPos"
                type="number"
                value={xPos}
                onChange={(e) => setXPos(e.target.value)}
                placeholder="0"
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="yPos">Y Position</Label>
              <Input
                id="yPos"
                type="number"
                value={yPos}
                onChange={(e) => setYPos(e.target.value)}
                placeholder="0"
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="zPos">Z Position</Label>
              <Input
                id="zPos"
                type="number"
                value={zPos}
                onChange={(e) => setZPos(e.target.value)}
                placeholder="0"
              />
            </div>
          </div>

          {/* Kind + capacity */}
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label>Kind</Label>
              <Select value={kind} onValueChange={setKind}>
                <SelectTrigger>
                  <SelectValue placeholder="None" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="none">None</SelectItem>
                  {LOCATION_KINDS.map((k) => (
                    <SelectItem key={k} value={k}>
                      {k}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <Label htmlFor="capacity">Capacity (slots)</Label>
              <Input
                id="capacity"
                type="number"
                value={capacity}
                onChange={(e) => setCapacity(e.target.value)}
                placeholder="Optional"
              />
            </div>
          </div>

          {/* Temperature zone + handling class */}
          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label>Temperature zone</Label>
              <Select value={temperatureZone} onValueChange={setTemperatureZone}>
                <SelectTrigger>
                  <SelectValue placeholder="None" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="none">None</SelectItem>
                  {TEMPERATURE_ZONES.map((t) => (
                    <SelectItem key={t} value={t}>
                      {t}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-2">
              <Label>Handling class</Label>
              <Select value={handlingClass} onValueChange={setHandlingClass}>
                <SelectTrigger>
                  <SelectValue placeholder="None" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="none">None</SelectItem>
                  {HANDLING_CLASSES.map((h) => (
                    <SelectItem key={h} value={h}>
                      {h}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>

          {/* PLC code */}
          <div className="space-y-2">
            <Label htmlFor="plcCode">PLC code</Label>
            <Input
              id="plcCode"
              value={plcCode}
              onChange={(e) => setPlcCode(e.target.value)}
              placeholder="Optional automation-system address"
            />
          </div>

          {/* Clearing location */}
          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <Label htmlFor="isClearing">Clearing location</Label>
              <Switch
                id="isClearing"
                checked={isClearing}
                onCheckedChange={(checked) => {
                  setIsClearing(checked);
                  if (errors.isClearing) setErrors((prev) => ({ ...prev, isClearing: '' }));
                }}
              />
            </div>
            <p className="text-xs text-muted-foreground">
              Single clearing location per site — only one location may be marked as clearing.
            </p>
            {errors.isClearing && (
              <p className="text-sm text-destructive">{errors.isClearing}</p>
            )}
          </div>

          {/* Exclude from putaway */}
          <div className="flex items-center justify-between">
            <Label htmlFor="excludedFromPutaway">Exclude from automatic putaway</Label>
            <Switch
              id="excludedFromPutaway"
              checked={excludedFromPutaway}
              onCheckedChange={setExcludedFromPutaway}
            />
          </div>
        </>
      )}

      {/* Form actions */}
      <div className="flex gap-2 pt-4">
        <Button onClick={handleSubmit} disabled={isPending}>
          {isPending ? 'Saving...' : 'Save'}
        </Button>
        <Button variant="outline" onClick={onCancel}>
          Cancel
        </Button>
      </div>
    </div>
  );
}
