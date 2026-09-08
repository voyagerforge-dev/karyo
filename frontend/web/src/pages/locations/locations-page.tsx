import { useMemo, useState } from 'react';
import { MapPin, Plus } from 'lucide-react';
import { cn } from '@/lib/utils';
import { usePermissions } from '@/hooks/use-permissions';
import { TONE_COLOR } from '@/components/master-detail/tones';
import {
  MasterDetailLayout,
  MasterList,
  MasterListRow,
  FilterChips,
  DetailEmptyState,
  type FilterChipOption,
} from '@/components/master-detail/master-detail';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Label } from '@/components/ui/label';
import { SectionCard } from '@/components/control/section-card';
import { LOCK_TYPES } from '@/types/location';
import { LocationForm } from './location-form';
import { LocationDetail } from './location-detail';
import { deriveLocation, occTone, type LocationView } from './location-derive';
import { useAllLocations, useLockLocation } from './use-locations';
import { useLocationContents } from './use-location-contents';

type LocationFilter = 'All' | 'Pick face' | 'Reserve' | 'Blocked';

const FILTER_OPTIONS: ReadonlyArray<FilterChipOption<LocationFilter>> = [
  { value: 'All', label: 'All' },
  { value: 'Pick face', label: 'Pick face' },
  { value: 'Reserve', label: 'Reserve' },
  { value: 'Blocked', label: 'Blocked' },
];

/** Left-list row: bin tile + code + type + occupancy bar + %. */
function LocationRow({
  view,
  active,
  onClick,
}: {
  view: LocationView;
  active: boolean;
  onClick: () => void;
}) {
  const tone = occTone(view.occPct, view.status);
  const color = TONE_COLOR[tone];
  return (
    <MasterListRow tone={tone} active={active} onClick={onClick}>
      <div className="flex items-center gap-3">
        <div className="flex size-[42px] flex-none flex-col items-center justify-center rounded-xl border border-border bg-background leading-none">
          <span className="numeric text-[8.5px] text-muted-foreground/70">{view.zoneShort}</span>
          <span className="numeric mt-0.5 text-[13px] font-bold text-foreground">{view.bay}</span>
        </div>
        <div className="min-w-0 flex-1">
          <div className="numeric truncate text-[13px] font-semibold text-foreground">{view.code}</div>
          <div className="mt-0.5 truncate text-[12px] text-muted-foreground">{view.typeName}</div>
          <div className="mt-[7px] h-[5px] overflow-hidden rounded bg-background">
            <div className="h-full rounded" style={{ width: `${view.occPct}%`, background: color }} />
          </div>
        </div>
        <span className="numeric text-[12px] font-bold" style={{ color }}>
          {view.occPct}%
        </span>
      </div>
    </MasterListRow>
  );
}

export function LocationsPage() {
  const { hasPermission } = usePermissions();
  const canWrite = hasPermission('layout-write');

  const { data, isLoading } = useAllLocations();
  const lockLocation = useLockLocation();
  const { byLocation, usedByLocation } = useLocationContents();

  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState<LocationFilter>('All');
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  // Task 10: explicit view/edit mode -- mirrors strategies-page.tsx's mode pattern. Create
  // stays dialog-based (unchanged); only edit swaps the form into the detail pane.
  const [mode, setMode] = useState<'view' | 'edit'>('view');

  // Block/unblock confirmation dialog state.
  const [blockTargetId, setBlockTargetId] = useState<number | null>(null);
  const [blockType, setBlockType] = useState(2); // default Quarantine

  // REAL: derive view models once per location-list change.
  const views = useMemo(() => (data?.content ?? []).map(deriveLocation), [data]);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return views.filter((v) => {
      if (filter === 'Pick face' && v.kind !== 'Pick face') return false;
      if (filter === 'Reserve' && !(v.kind === 'Reserve' || v.kind === 'Floor')) return false;
      if (filter === 'Blocked' && v.status !== 'Blocked') return false;
      if (q && !(v.code.toLowerCase().includes(q) || v.zoneLabel.toLowerCase().includes(q))) {
        return false;
      }
      return true;
    });
  }, [views, search, filter]);

  const selected = useMemo(
    () => filtered.find((v) => v.id === selectedId) ?? filtered[0] ?? null,
    [filtered, selectedId],
  );

  // Raw response for the selected view -- LocationForm edits the entity shape, not the
  // derived LocationView.
  const selectedRaw = useMemo(
    () => (data?.content ?? []).find((l) => l.id === selected?.id) ?? null,
    [data, selected],
  );

  const blockTarget = useMemo(
    () => views.find((v) => v.id === blockTargetId) ?? null,
    [views, blockTargetId],
  );
  const blockTargetLocked = (blockTarget?.lockType ?? 0) > 0;

  // areaId is required to create a location; reuse the selected bin's area, else
  // fall back to the first location's area in the list.
  const defaultAreaId = useMemo(() => {
    const content = data?.content ?? [];
    const sel = content.find((l) => l.id === (selected?.id ?? -1));
    return sel?.area?.id ?? content[0]?.area?.id;
  }, [data, selected]);

  function confirmBlock() {
    if (blockTarget) {
      lockLocation.mutate({
        id: blockTarget.id,
        data: { lockType: blockTargetLocked ? 0 : blockType },
      });
    }
    setBlockTargetId(null);
  }

  return (
    <div className="space-y-4" data-testid="locations-page">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Locations</h1>
          <p className="mt-0.5 text-sm text-muted-foreground">
            {isLoading ? 'Loading…' : `${views.length} bins`}
          </p>
        </div>
        <Dialog open={createOpen} onOpenChange={setCreateOpen}>
          <DialogTrigger asChild>
            <button
              type="button"
              disabled={!canWrite}
              className="flex h-9 items-center gap-1.5 rounded-[10px] bg-primary px-3.5 text-[13px] font-bold text-primary-foreground transition-opacity hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40"
            >
              <Plus className="size-4" />
              New location
            </button>
          </DialogTrigger>
          <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-lg">
            <DialogHeader>
              <DialogTitle>New location</DialogTitle>
            </DialogHeader>
            <LocationForm
              level="locations"
              entity={null}
              parentId={defaultAreaId}
              onSave={() => setCreateOpen(false)}
              onCancel={() => setCreateOpen(false)}
            />
          </DialogContent>
        </Dialog>
      </div>

      <MasterDetailLayout
        list={
          <MasterList
            searchValue={search}
            onSearchChange={setSearch}
            searchPlaceholder="Search bin or zone…"
            chips={
              <FilterChips<LocationFilter>
                options={FILTER_OPTIONS}
                value={filter}
                onChange={setFilter}
              />
            }
          >
            {isLoading ? (
              <p className="px-1 py-4 text-sm text-muted-foreground">Loading locations…</p>
            ) : filtered.length === 0 ? (
              <p className="px-1 py-4 text-sm text-muted-foreground">No locations match your filters.</p>
            ) : (
              filtered.map((v) => (
                <LocationRow
                  key={v.id}
                  view={v}
                  active={mode === 'view' && selected?.id === v.id}
                  onClick={() => {
                    setSelectedId(v.id);
                    setMode('view');
                  }}
                />
              ))
            )}
          </MasterList>
        }
        detail={
          mode === 'edit' && selected && selectedRaw ? (
            <SectionCard title={`Edit ${selected.code}`}>
              <LocationForm
                level="locations"
                entity={selectedRaw}
                parentId={selectedRaw.area.id}
                onSave={() => setMode('view')}
                onCancel={() => setMode('view')}
              />
            </SectionCard>
          ) : selected ? (
            <LocationDetail
              view={selected}
              contents={byLocation.get(selected.id) ?? []}
              used={usedByLocation.get(selected.id) ?? 0}
              canWrite={canWrite}
              onBlock={() => {
                setBlockType(2);
                setBlockTargetId(selected.id);
              }}
              onEdit={() => setMode('edit')}
            />
          ) : (
            <DetailEmptyState
              icon={<MapPin className="size-8 opacity-40" />}
              message={isLoading ? 'Loading locations…' : 'Select a location to see its details.'}
            />
          )
        }
      />

      {/* Block / unblock confirmation — real lock mutation. */}
      <AlertDialog open={blockTargetId !== null} onOpenChange={(o) => !o && setBlockTargetId(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {blockTargetLocked ? 'Unblock location' : 'Block location'}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {blockTargetLocked
                ? `Release the block on "${blockTarget?.code}" (currently: ${blockTarget?.lockTypeName})?`
                : `Select a block reason for "${blockTarget?.code}". Blocked bins are excluded from picking.`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          {!blockTargetLocked && (
            <div className={cn('space-y-2 py-2')}>
              <Label>Block reason</Label>
              <Select value={String(blockType)} onValueChange={(v) => setBlockType(Number(v))}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {LOCK_TYPES.filter((lt) => lt.code > 0).map((lt) => (
                    <SelectItem key={lt.code} value={String(lt.code)}>
                      {lt.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          )}
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              variant={blockTargetLocked ? 'default' : 'destructive'}
              onClick={confirmBlock}
            >
              {blockTargetLocked ? 'Unblock' : 'Block'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
