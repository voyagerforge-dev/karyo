import { useState } from 'react';
import { Lock } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import { useLicense } from '@/features/license/use-license';
import { RELEASE_MODES, STREAM_KEYS, type OrderStrategyResponse, type ReleaseMode } from '@/types/strategies';
import { patchIdField } from '../orders/order-form';
import { LocationPicker, type PickedLocation } from '../receiving/location-picker';
import { useCreateOrderStrategy, useUpdateOrderStrategy } from './use-strategies';

const CARTONIZATION_PACKOUT = 'CARTONIZATION';
const STREAMING_ADDON = 'advanced-fulfillment';

const RELEASE_MODE_HINTS: Record<ReleaseMode, string> = {
  MANUAL: 'MANUAL: release by hand or by API',
  WAVE: 'WAVE: eligible for wave selection (set waveAutoRelease to schedule it)',
  STREAM:
    'STREAM: the streaming scheduler releases and pushes to picking automatically; needs the karyo.streaming.enabled system property',
};

const STREAM_KEY_SET = new Set<string>(STREAM_KEYS);

/** The five release-mode/stream keys are owned by the typed fields below -- strip them
 * from the free-form JSON textarea so they never appear twice. */
function stripStreamKeys(ext: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(ext)) {
    if (!STREAM_KEY_SET.has(key)) out[key] = value;
  }
  return out;
}

/** The backend parser is case-insensitive, so an API-written `"stream"` IS a STREAM strategy --
 * uppercase before validating, or the form renders MANUAL and silently flips it on the next save. */
function initialReleaseMode(ext?: Record<string, unknown>): ReleaseMode {
  const value = ext?.releaseMode;
  const upper = typeof value === 'string' ? value.trim().toUpperCase() : '';
  return (RELEASE_MODES as readonly string[]).includes(upper) ? (upper as ReleaseMode) : 'MANUAL';
}

/** Parses a numeric field, falling back to the documented default only on empty/NaN input --
 * `|| d` would also replace a deliberate 0 (used to force immediate escalation/stall). */
function numOr(raw: string, fallback: number): number {
  const n = Number(raw);
  return raw.trim() === '' || Number.isNaN(n) ? fallback : n;
}

interface Props {
  strategy?: OrderStrategyResponse;
  onClose: () => void;
}

// shortPickMode is a real closed backend enum — these are the only 4 values it accepts.
const SHORT_PICK_MODES = [
  { value: 'FOLLOW_UP', label: 'Follow up' },
  { value: 'FOLLOW_UP_THEN_SUBSTITUTE', label: 'Follow up, then substitute' },
  { value: 'SUBSTITUTE_ONLY', label: 'Substitute only' },
  { value: 'NONE', label: 'None' },
] as const;

export function OrderStrategyForm({ strategy, onClose }: Props) {
  const isEdit = !!strategy;
  const createMutation = useCreateOrderStrategy();
  const updateMutation = useUpdateOrderStrategy();
  const license = useLicense();

  const [name, setName] = useState(strategy?.name ?? '');
  const [useLockedStock, setUseLockedStock] = useState(strategy?.useLockedStock ?? false);
  const [preferComplete, setPreferComplete] = useState(strategy?.preferComplete ?? true);
  const [preferMatching, setPreferMatching] = useState(strategy?.preferMatching ?? false);
  const [shortPickMode, setShortPickMode] = useState(
    strategy?.shortPickMode ?? 'FOLLOW_UP_THEN_SUBSTITUTE',
  );
  const [enforceLot, setEnforceLot] = useState(strategy?.enforceLot ?? false);
  const [completeHandling, setCompleteHandling] = useState(
    String(strategy?.completeHandling ?? 0),
  );
  const [shortfallStrategy, setShortfallStrategy] = useState(
    strategy?.shortfallStrategy ?? 'PARTIAL_SHIP',
  );
  const [pickDifferenceStrategy, setPickDifferenceStrategy] = useState(
    strategy?.pickDifferenceStrategy ?? 'LEAVE',
  );
  const [packoutStrategy, setPackoutStrategy] = useState(
    strategy?.packoutStrategy ?? 'ONE_TO_ONE',
  );
  const [extJson, setExtJson] = useState(
    JSON.stringify(stripStreamKeys(strategy?.extensionProperties ?? {}), null, 2),
  );
  const [releaseMode, setReleaseMode] = useState<ReleaseMode>(
    initialReleaseMode(strategy?.extensionProperties),
  );
  const [streamBatchSize, setStreamBatchSize] = useState(
    String(strategy?.extensionProperties?.streamBatchSize ?? 50),
  );
  const [streamMaxWaitSeconds, setStreamMaxWaitSeconds] = useState(
    String(strategy?.extensionProperties?.streamMaxWaitSeconds ?? 30),
  );
  const [streamAbandonSeconds, setStreamAbandonSeconds] = useState(
    String(strategy?.extensionProperties?.streamAbandonSeconds ?? 1800),
  );
  const [streamTimingStrategy, setStreamTimingStrategy] = useState(
    String(strategy?.extensionProperties?.streamTimingStrategy ?? 'time-size'),
  );
  const [sendToPacking, setSendToPacking] = useState(strategy?.sendToPacking ?? false);
  const [sendToShipping, setSendToShipping] = useState(strategy?.sendToShipping ?? false);
  const [createShippingOrder, setCreateShippingOrder] = useState(
    strategy?.createShippingOrder ?? false,
  );
  const [createTypeOrders, setCreateTypeOrders] = useState(strategy?.createTypeOrders ?? false);
  const [destination, setDestination] = useState<PickedLocation | null>(
    strategy?.defaultDestinationLocationId != null
      ? { id: strategy.defaultDestinationLocationId, name: strategy.defaultDestinationLocationName ?? '' }
      : null,
  );
  const [errors, setErrors] = useState<{ name?: string; ext?: string }>({});

  function parseExt(): Record<string, unknown> | null {
    try {
      const parsed = JSON.parse(extJson || '{}');
      if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return null;
      return parsed as Record<string, unknown>;
    } catch {
      return null;
    }
  }

  function handleSubmit() {
    const ext = parseExt();
    const next: typeof errors = {};
    if (!isEdit && !name.trim()) next.name = 'Name is required';
    if (ext === null) next.ext = 'Extension properties must be a JSON object';
    setErrors(next);
    if (Object.keys(next).length > 0) return;

    const shared = {
      useLockedStock,
      preferComplete,
      preferMatching,
      completeHandling: Number(completeHandling) || 0,
      enforceLot,
      shortPickMode,
      shortfallStrategy: shortfallStrategy.trim(),
      pickDifferenceStrategy: pickDifferenceStrategy.trim(),
      packoutStrategy: packoutStrategy.trim(),
      // The five stream/release keys are typed fields; they own their keys and always win
      // over anything left in the free-form JSON textarea (the textarea seed already
      // stripped them, but a hand-edit could reintroduce one).
      extensionProperties: {
        ...ext!,
        releaseMode,
        ...(releaseMode === 'STREAM'
          ? {
              streamBatchSize: numOr(streamBatchSize, 50),
              streamMaxWaitSeconds: numOr(streamMaxWaitSeconds, 30),
              streamAbandonSeconds: numOr(streamAbandonSeconds, 1800),
              streamTimingStrategy: streamTimingStrategy.trim() || 'time-size',
            }
          : {}),
      },
      sendToPacking,
      sendToShipping,
      createShippingOrder,
      createTypeOrders,
    };

    if (isEdit) {
      // :1457: defaultDestinationLocationId is tri-state on the update DTO (Patchable<Long>) --
      // patchIdField sends explicit null on clear, undefined when there was nothing to clear.
      updateMutation.mutate(
        {
          id: strategy.id,
          ...shared,
          defaultDestinationLocationId: patchIdField(
            strategy.defaultDestinationLocationId,
            destination ? destination.id : null,
          ),
        },
        { onSuccess: onClose },
      );
    } else {
      // Create's defaultDestinationLocationId stays plain-nullable (CreateOrderStrategyRequest) --
      // there is nothing to "clear" on a brand new strategy.
      createMutation.mutate(
        {
          name: name.trim(),
          ...shared,
          defaultDestinationLocationId: destination ? destination.id : undefined,
        },
        { onSuccess: onClose },
      );
    }
  }

  const isSubmitting = createMutation.isPending || updateMutation.isPending;
  const packoutLocked =
    packoutStrategy.trim().toUpperCase() === CARTONIZATION_PACKOUT && !license.isEntitled('cartonization');
  // Does not block saving -- the strategy still saves as STREAM; the streaming engine
  // itself is what checks the license at run time.
  const streamModeLocked = releaseMode === 'STREAM' && !license.isEntitled(STREAMING_ADDON);

  return (
    <div className="space-y-6">
      <div className="space-y-2">
        <Label htmlFor="os-name">Name</Label>
        <Input
          id="os-name"
          value={name}
          disabled={isEdit}
          onChange={(e) => setName(e.target.value)}
          placeholder="e.g. ECOMM-FAST"
        />
        {errors.name && <p className="text-sm text-destructive">{errors.name}</p>}
      </div>

      <div className="space-y-4">
        <h4 className="text-sm font-medium text-muted-foreground">Reservation</h4>

        <div className="flex items-center justify-between">
          <Label htmlFor="os-locked">Use locked stock</Label>
          <Switch id="os-locked" checked={useLockedStock} onCheckedChange={setUseLockedStock} />
        </div>

        <div className="flex items-center justify-between">
          <Label htmlFor="os-complete">Prefer complete</Label>
          <Switch id="os-complete" checked={preferComplete} onCheckedChange={setPreferComplete} />
        </div>

        <div className="flex items-center justify-between">
          <Label htmlFor="os-matching">Prefer matching</Label>
          <Switch id="os-matching" checked={preferMatching} onCheckedChange={setPreferMatching} />
        </div>
      </div>

      <div className="space-y-4 border-t pt-4">
        <h4 className="text-sm font-medium text-muted-foreground">Picking</h4>

        <div className="space-y-2">
          <Label htmlFor="os-shortpick">Short pick mode</Label>
          <Select value={shortPickMode} onValueChange={setShortPickMode}>
            <SelectTrigger id="os-shortpick">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {SHORT_PICK_MODES.map((opt) => (
                <SelectItem key={opt.value} value={opt.value}>
                  {opt.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="flex items-center justify-between">
          <Label htmlFor="os-enforcelot">Enforce lot</Label>
          <Switch id="os-enforcelot" checked={enforceLot} onCheckedChange={setEnforceLot} />
        </div>

        <div className="space-y-2">
          <Label htmlFor="os-completehandling">Complete handling</Label>
          <Input
            id="os-completehandling"
            type="number"
            value={completeHandling}
            onChange={(e) => setCompleteHandling(e.target.value)}
          />
        </div>

        <div className="flex items-center justify-between">
          <Label htmlFor="os-typeorders">Split by picking type</Label>
          <Switch id="os-typeorders" checked={createTypeOrders} onCheckedChange={setCreateTypeOrders} />
        </div>
        <p className="text-xs text-muted-foreground">
          Releases a mixed order as one pick order per derived picking type (COMPLETE vs PICK)
          instead of a single pick order.
        </p>
      </div>

      <div className="space-y-4 border-t pt-4">
        <h4 className="text-sm font-medium text-muted-foreground">Progression</h4>

        <div className="space-y-2">
          <Label htmlFor="os-releasemode">Release mode</Label>
          <Select value={releaseMode} onValueChange={(v) => setReleaseMode(v as ReleaseMode)}>
            <SelectTrigger id="os-releasemode" data-testid="os-releasemode">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {RELEASE_MODES.map((mode) => (
                <SelectItem key={mode} value={mode}>
                  {mode}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <p className="text-xs text-muted-foreground">{RELEASE_MODE_HINTS[releaseMode]}</p>
          {streamModeLocked && (
            <p
              data-testid="stream-mode-locked"
              className="flex items-center gap-1 text-xs text-muted-foreground"
            >
              <Lock className="size-3.5" strokeWidth={2} />
              Requires the Advanced Fulfillment add-on.
            </p>
          )}
        </div>

        {releaseMode === 'STREAM' && (
          <div className="space-y-4 rounded-lg border border-border p-3">
            <div className="space-y-2">
              <Label htmlFor="os-streambatch">Stream batch size</Label>
              <Input
                id="os-streambatch"
                type="number"
                min={1}
                max={1000}
                value={streamBatchSize}
                onChange={(e) => setStreamBatchSize(e.target.value)}
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="os-streammaxwait">Stream max wait (seconds)</Label>
              <Input
                id="os-streammaxwait"
                type="number"
                min={0}
                value={streamMaxWaitSeconds}
                onChange={(e) => setStreamMaxWaitSeconds(e.target.value)}
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="os-streamabandon">Stream abandon (seconds)</Label>
              <Input
                id="os-streamabandon"
                type="number"
                min={0}
                value={streamAbandonSeconds}
                onChange={(e) => setStreamAbandonSeconds(e.target.value)}
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="os-streamtiming">Stream timing strategy</Label>
              <Input
                id="os-streamtiming"
                value={streamTimingStrategy}
                onChange={(e) => setStreamTimingStrategy(e.target.value)}
              />
              <p className="text-xs text-muted-foreground">SPI strategy key -- built-in: time-size</p>
            </div>
          </div>
        )}

        <div className="flex items-center justify-between">
          <Label htmlFor="os-sendtopacking">Send to packing</Label>
          <Switch id="os-sendtopacking" checked={sendToPacking} onCheckedChange={setSendToPacking} />
        </div>
        <p className="text-xs text-muted-foreground">
          A picked order parks in PACKING instead of stopping at PICKED; packing still moves it on.
        </p>

        <div className="flex items-center justify-between">
          <Label htmlFor="os-sendtoshipping">Send to shipping</Label>
          <Switch id="os-sendtoshipping" checked={sendToShipping} onCheckedChange={setSendToShipping} />
        </div>
        <p className="text-xs text-muted-foreground">
          A packed order parks in SHIPPING instead of stopping at PACKED; dispatch still moves it on.
        </p>

        <div className="flex items-center justify-between">
          <Label htmlFor="os-createshippingorder">Auto-open shipment</Label>
          <Switch
            id="os-createshippingorder"
            checked={createShippingOrder}
            onCheckedChange={setCreateShippingOrder}
          />
        </div>
        <p className="text-xs text-muted-foreground">
          Opens the shipment automatically when picking completes, instead of waiting for an
          operator to post one by hand.
        </p>

        <div className="space-y-2">
          <Label htmlFor="os-destination">Default destination</Label>
          <LocationPicker inputId="os-destination" value={destination} onChange={setDestination} />
          <p className="text-xs text-muted-foreground">
            Fallback pick-order destination when the order itself has none set.
          </p>
        </div>
      </div>

      <div className="space-y-4 border-t pt-4">
        <h4 className="text-sm font-medium text-muted-foreground">Fulfillment</h4>

        <div className="space-y-2">
          <Label htmlFor="os-shortfall">Shortfall strategy</Label>
          <Input
            id="os-shortfall"
            value={shortfallStrategy}
            onChange={(e) => setShortfallStrategy(e.target.value)}
          />
          <p className="text-xs text-muted-foreground">
            SPI strategy name — built-in: PARTIAL_SHIP
          </p>
        </div>

        <div className="space-y-2">
          <Label htmlFor="os-pickdiff">Pick difference strategy</Label>
          <Input
            id="os-pickdiff"
            value={pickDifferenceStrategy}
            onChange={(e) => setPickDifferenceStrategy(e.target.value)}
          />
          <p className="text-xs text-muted-foreground">SPI strategy name — built-in: LEAVE</p>
        </div>

        <div className="space-y-2">
          <Label htmlFor="os-packout">Packout strategy</Label>
          <Input
            id="os-packout"
            value={packoutStrategy}
            onChange={(e) => setPackoutStrategy(e.target.value)}
          />
          <p className="text-xs text-muted-foreground">
            SPI strategy name, built-in: ONE_TO_ONE. Paid: CARTONIZATION (re-packs into boxes).
          </p>
          {packoutLocked && (
            <p
              data-testid="packout-cartonization-locked"
              className="flex items-center gap-1 text-xs text-muted-foreground"
            >
              <Lock className="size-3.5" strokeWidth={2} />
              Requires the Cartonization add-on.
            </p>
          )}
        </div>
      </div>

      <div className="space-y-2 border-t pt-4">
        <Label htmlFor="os-ext">Extension properties (JSON)</Label>
        <Textarea
          id="os-ext"
          value={extJson}
          onChange={(e) => setExtJson(e.target.value)}
          rows={6}
          className="font-mono text-[13px]"
        />
        {errors.ext && <p className="text-sm text-destructive">{errors.ext}</p>}
      </div>

      <div className="flex justify-end gap-2 border-t pt-4">
        <Button variant="outline" onClick={onClose}>Cancel</Button>
        <Button onClick={handleSubmit} disabled={isSubmitting} data-testid="order-strategy-submit">
          {isSubmitting ? 'Saving...' : isEdit ? 'Save' : 'Create'}
        </Button>
      </div>
    </div>
  );
}
