import { useDeferredValue, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import { ClipboardCheck, Plus } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  MasterDetailLayout,
  MasterList,
  MasterListRow,
  DetailEmptyState,
} from '@/components/master-detail/master-detail';
import { SectionCard } from '@/components/control/section-card';
import { StatusPill } from '@/components/control/status-pill';
import type { EntityTone } from '@/components/master-detail/tones';
import { Skeleton } from '@/components/ui/skeleton';
import { useSessions, useStartCount, useCampaigns, useOrderSession } from './use-cycle-count';
import { SessionDetail } from './session-detail';
import { CampaignsCard } from './campaigns-card';
import type { CountSessionSummaryView, CountType } from '@/types/cycle-count';

// ---------------------------------------------------------------------------
// Session state / progress helpers (ported from the retired session-columns.tsx)
// ---------------------------------------------------------------------------

interface SessionStatusInfo {
  label: string;
  tone: EntityTone;
}

function getSessionStatus(session: Pick<CountSessionSummaryView, 'state'>): SessionStatusInfo {
  if (session.state === 100) return { label: 'Open', tone: 'lime' };
  if (session.state === 700) return { label: 'Closed', tone: 'grey' };
  return { label: `State ${session.state}`, tone: 'grey' };
}

function sessionProgress(session: CountSessionSummaryView): string {
  // St5: a full inventory reports the locations it walked past (reserved / already locked).
  // Only the start response carries them (not persisted), so this is usually absent.
  const skipped = session.skippedLocations?.length ?? 0;
  const skippedLabel = skipped > 0 ? `${skipped} skipped` : '';
  const total = session.orderCount;
  if (total === 0) return skippedLabel || '—';
  const done = `${session.finishedCount}/${total} done${session.countedCount > 0 ? `, ${session.countedCount} in review` : ''}`;
  return skippedLabel ? `${done} · ${skippedLabel}` : done;
}

// ---------------------------------------------------------------------------
// Start-count form (now rendered in the detail slot, like receiving's
// NewReceiptForm)
// ---------------------------------------------------------------------------

interface StartCountFormProps {
  onStarted: (sessionId: number) => void;
}

function StartCountForm({ onStarted }: StartCountFormProps) {
  const startCount = useStartCount();
  const { data: campaigns } = useCampaigns();
  const [countType, setCountType] = useState<CountType>('CYCLE');
  const [locationIdsRaw, setLocationIdsRaw] = useState('');
  const [areaIdRaw, setAreaIdRaw] = useState('');
  const [locationNamePattern, setLocationNamePattern] = useState('');
  const [blindCount, setBlindCount] = useState(true);
  const [campaignId, setCampaignId] = useState<string>('');

  // St5: a full inventory owns its own scope (every location this client owns) -- the backend
  // 422s any location/area/pattern input on it, so the form hides those fields entirely.
  const isFullInventory = countType === 'END_OF_PERIOD';

  // Only OPEN(100) campaigns OF THE SELECTED TYPE accept the start (St1 + St5) -- a CLOSED
  // campaign, or one of the other type, would 409 server-side.
  const openCampaigns = (campaigns ?? []).filter((c) => c.state === 100 && c.type === countType);

  const handleTypeChange = (value: string) => {
    setCountType(value as CountType);
    // The campaign list is type-filtered -- a selection made under the other type would 409.
    setCampaignId('');
  };

  const handleStart = () => {
    const locationIds = locationIdsRaw
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
      .map(Number)
      .filter((n) => !isNaN(n));
    const areaId = areaIdRaw.trim() ? Number(areaIdRaw.trim()) : undefined;
    const pattern = locationNamePattern.trim();

    startCount.mutate(
      {
        type: isFullInventory ? 'END_OF_PERIOD' : undefined,
        locationIds: !isFullInventory && locationIds.length > 0 ? locationIds : undefined,
        areaId: !isFullInventory && areaId && !isNaN(areaId) ? areaId : undefined,
        locationNamePattern: !isFullInventory && pattern.length > 0 ? pattern : undefined,
        blindCount,
        campaignId: campaignId ? Number(campaignId) : undefined,
      },
      {
        onSuccess: (session) => {
          setLocationIdsRaw('');
          setAreaIdRaw('');
          setLocationNamePattern('');
          setCampaignId('');
          onStarted(session.id);
        },
      },
    );
  };

  const hasScope =
    isFullInventory ||
    locationIdsRaw.trim().length > 0 ||
    areaIdRaw.trim().length > 0 ||
    locationNamePattern.trim().length > 0;

  return (
    <div className="space-y-3">
      <div className="space-y-1">
        <Label className="text-xs">Count type</Label>
        <Select value={countType} onValueChange={handleTypeChange}>
          <SelectTrigger data-testid="count-type-select">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="CYCLE">Cycle count (selected locations)</SelectItem>
            <SelectItem value="END_OF_PERIOD">Full inventory</SelectItem>
          </SelectContent>
        </Select>
      </div>
      {isFullInventory ? (
        <p className="text-[12.5px] text-muted-foreground" data-testid="full-inventory-note">
          Counts every location owned by this client, empty ones included, and locks each of them
          until its order is finished. Locations with reserved stock or an existing lock are
          skipped and listed on the started session. Skipped locations can be counted afterwards
          with a targeted cycle count in the same campaign once their reservations clear.
        </p>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="space-y-1">
            <Label htmlFor="location-ids" className="text-xs">
              Location IDs (comma-separated)
            </Label>
            <Input
              id="location-ids"
              placeholder="e.g. 1, 2, 3"
              value={locationIdsRaw}
              onChange={(e) => setLocationIdsRaw(e.target.value)}
              data-testid="location-ids-input"
            />
          </div>
          <div className="space-y-1">
            <Label htmlFor="area-id" className="text-xs">
              Area ID (counts all locations in area)
            </Label>
            <Input
              id="area-id"
              placeholder="e.g. 10"
              value={areaIdRaw}
              onChange={(e) => setAreaIdRaw(e.target.value)}
              data-testid="area-id-input"
            />
          </div>
          <div className="space-y-1">
            <Label htmlFor="location-pattern" className="text-xs">
              Location pattern (SQL LIKE)
            </Label>
            <Input
              id="location-pattern"
              placeholder="A-01-%"
              value={locationNamePattern}
              onChange={(e) => setLocationNamePattern(e.target.value)}
              data-testid="location-pattern-input"
            />
          </div>
        </div>
      )}
      <div className="space-y-1">
        <Label className="text-xs">Campaign (optional)</Label>
        <Select
          value={campaignId || '__none__'}
          onValueChange={(v) => setCampaignId(v === '__none__' ? '' : v)}
        >
          <SelectTrigger data-testid="campaign-select">
            <SelectValue placeholder="No campaign" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="__none__">No campaign</SelectItem>
            {openCampaigns.map((c) => (
              <SelectItem key={c.id} value={String(c.id)}>
                {c.campaignNumber} — {c.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Switch
            id="blind-count"
            checked={blindCount}
            onCheckedChange={setBlindCount}
            data-testid="blind-count-switch"
          />
          <Label htmlFor="blind-count" className="text-xs cursor-pointer">
            Blind count (hide planned amounts from counter)
          </Label>
        </div>
        <Button
          onClick={handleStart}
          disabled={!hasScope || startCount.isPending}
          data-testid="start-count-button"
        >
          <ClipboardCheck className="mr-2 size-4" />
          Start count
        </Button>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Master list row
// ---------------------------------------------------------------------------

function SessionRow({
  session,
  active,
  onClick,
}: {
  session: CountSessionSummaryView;
  active: boolean;
  onClick: () => void;
}) {
  const status = getSessionStatus(session);
  return (
    <MasterListRow
      tone={status.tone}
      active={active}
      onClick={onClick}
      testId={`session-row-${session.id}`}
    >
      <div className="flex items-center justify-between gap-2">
        <span className="numeric text-[13px] font-semibold text-foreground">
          {session.sessionNumber}
        </span>
        <div className="flex shrink-0 items-center gap-2">
          {/* St5: full inventories are rare and warehouse-wide -- worth calling out.
              A plain CYCLE session gets no badge (that's the default, not news). */}
          {session.type === 'END_OF_PERIOD' && (
            <StatusPill label="Full inventory" tone="amber" />
          )}
          {/* blindCount is not exposed by CountSessionView (it's per-order on the
              entity, not surfaced by any view today) -- no fabricated Blind/Standard
              pill here. */}
          <StatusPill label={status.label} tone={status.tone} />
        </div>
      </div>
      <p className="mt-1.5 text-[12.5px] text-foreground/70">{sessionProgress(session)}</p>
    </MasterListRow>
  );
}

// ---------------------------------------------------------------------------
// Main page
// ---------------------------------------------------------------------------

export function CycleCountPage() {
  const { data, isLoading } = useSessions();

  const [search, setSearch] = useState('');
  const deferredSearch = useDeferredValue(search);

  const [selectedSessionId, setSelectedSessionId] = useState<number | undefined>(undefined);
  const [formOpen, setFormOpen] = useState(false);
  const [deepLinkOrderId, setDeepLinkOrderId] = useState<number | undefined>(undefined);

  // `?order={countOrderId}` deep link (from the Tasks COUNT pane's "Open in
  // Cycle Count" link): resolve the order's owning session, select it and
  // feed the order id into the session pane, then strip the param. No match
  // (or the order id is malformed) is an honest no-op -- the page just
  // renders as usual.
  //
  // GET /count-sessions now returns the summary projection (no nested orders --
  // defect-burndown row 8), so the session containing the order can no longer be found by
  // scanning the sessions list; useOrderSession resolves it with a direct one-shot lookup.
  const [searchParams, setSearchParams] = useSearchParams();
  const orderParam = searchParams.get('order');
  const rawOrderId = orderParam ? Number(orderParam) : NaN;
  const validOrderId = orderParam && !Number.isNaN(rawOrderId) ? rawOrderId : undefined;
  const { data: deepLinkSessionId, isFetched: deepLinkFetched } = useOrderSession(validOrderId);
  const deepLinkHandledRef = useRef(false);
  useEffect(() => {
    if (deepLinkHandledRef.current) return;
    if (!orderParam) return;
    if (validOrderId != null && !deepLinkFetched) return; // still resolving -- wait for the next render
    deepLinkHandledRef.current = true;
    if (validOrderId != null && deepLinkSessionId != null) {
      setSelectedSessionId(deepLinkSessionId);
      setFormOpen(false);
      setDeepLinkOrderId(validOrderId);
    }
    setSearchParams({}, { replace: true });
  }, [orderParam, validOrderId, deepLinkSessionId, deepLinkFetched, searchParams, setSearchParams]);

  const rows = useMemo(() => {
    const all = data ?? [];
    const q = deferredSearch.trim().toLowerCase();
    if (!q) return all;
    return all.filter((s: CountSessionSummaryView) =>
      s.sessionNumber.toLowerCase().includes(q),
    );
  }, [data, deferredSearch]);

  const handleStarted = (sessionId: number) => {
    setSelectedSessionId(sessionId);
    setFormOpen(false);
  };

  return (
    <div className="space-y-4" data-testid="cycle-count-page">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="font-display text-2xl font-bold tracking-[-0.02em]">Cycle Count</h1>
          <p className="text-sm text-muted-foreground">
            Start a count session, enter quantities blind, and review discrepancies
          </p>
        </div>
        <button
          type="button"
          onClick={() => setFormOpen(true)}
          className="flex h-9 items-center gap-1.5 rounded-[9px] bg-primary px-3.5 text-[13px] font-bold text-primary-foreground"
        >
          <Plus className="size-4" />
          New count
        </button>
      </div>

      <CampaignsCard />

      <MasterDetailLayout
        list={
          <MasterList
            searchValue={search}
            onSearchChange={setSearch}
            searchPlaceholder="Search by session number…"
          >
            {isLoading ? (
              <>
                <Skeleton className="h-[68px] w-full rounded-xl" />
                <Skeleton className="h-[68px] w-full rounded-xl" />
              </>
            ) : rows.length === 0 ? (
              <p className="px-1 py-6 text-center text-sm text-muted-foreground">
                No count sessions match.
              </p>
            ) : (
              rows.map((session) => (
                <SessionRow
                  key={session.id}
                  session={session}
                  active={session.id === selectedSessionId && !formOpen}
                  onClick={() => {
                    setSelectedSessionId(session.id);
                    setFormOpen(false);
                    // I-2: a manual session pick supersedes any pending deep link -- without
                    // this, returning to the deep-linked session after SessionDetail's
                    // per-session remount (see the `key` below) would re-open its order.
                    setDeepLinkOrderId(undefined);
                  }}
                />
              ))
            )}
          </MasterList>
        }
        detail={
          formOpen ? (
            <SectionCard title="New count">
              <StartCountForm onStarted={handleStarted} />
            </SectionCard>
          ) : selectedSessionId != null ? (
            // I-2: keyed on the session id so activeOrderId/activeMode reset on every
            // session switch -- without this, session B's pane showed session A's
            // leftover count-entry/review form (SessionDetail has no id-scoped guard).
            <SessionDetail
              key={selectedSessionId}
              sessionId={selectedSessionId}
              initialOrderId={deepLinkOrderId}
            />
          ) : (
            <DetailEmptyState
              icon={<ClipboardCheck className="size-8 opacity-40" />}
              message="Select a session"
            />
          )
        }
      />
    </div>
  );
}
