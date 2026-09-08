import { useMemo, useState } from 'react';
import { Layers, Lock, Plus } from 'lucide-react';
import {
  MasterDetailLayout,
  MasterList,
  MasterListRow,
  FilterChips,
  DetailEmptyState,
} from '@/components/master-detail/master-detail';
import type { FilterChipOption } from '@/components/master-detail/master-detail';
import { StatusPill } from '@/components/control/status-pill';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { usePermissions } from '@/hooks/use-permissions';
import { useLicense } from '@/features/license/use-license';
import { useWaves } from '@/features/waves/use-waves';
import type { WaveResponse, WaveStateName } from '@/types/waves';
import { WAVE_STATES } from '@/types/waves';
import { WAVE_STATE_TONE, WAVE_STATE_LABEL } from './wave-status';
import { WaveDetail } from './wave-detail';
import { CreateWaveDialog } from './create-wave-dialog';
import { RulesTab } from './rules/rules-tab';

/**
 * Waves screen (v2.x Advanced Fulfillment pack, Task 10): the desktop master-detail for
 * `karyo-wave` (Tasks 5-7 backend). Gated behind the `advanced-fulfillment` license
 * entitlement -- same key as cross-docking (the first pack member). The gate renders BEFORE
 * any data hook runs (a separate `WavesBoard` component mounts only once entitled), mirroring
 * `monitors-page.tsx` / `forecasting-page.tsx` so an unentitled tenant never fires a 403.
 */

type BoardTab = 'WAVES' | 'RULES';
type WaveFilter = 'ALL' | WaveStateName;

const FILTERS: ReadonlyArray<FilterChipOption<WaveFilter>> = [
  { value: 'ALL', label: 'All' },
  ...WAVE_STATES.map((s) => ({ value: s as WaveFilter, label: WAVE_STATE_LABEL[s] })),
];

function LockedWavesPanel() {
  return (
    <div
      data-testid="waves-locked"
      className="flex flex-col items-center justify-center gap-3 rounded-2xl border border-border bg-card px-8 py-20 text-center"
    >
      <div className="flex size-12 items-center justify-center rounded-full bg-signal/10">
        <Lock className="size-5 text-primary" strokeWidth={2} />
      </div>
      <h1 className="font-display m-0 text-[20px] font-bold tracking-[-0.02em] text-foreground">
        Wave fulfillment is a paid add-on
      </h1>
      <p className="m-0 max-w-md text-[13px] text-muted-foreground">
        Group orders into waves, allocate stock by priority, and batch-pick across orders.
        Contact your account team to enable Advanced Fulfillment for this tenant.
      </p>
    </div>
  );
}

function WaveRow({
  wave,
  active,
  onClick,
}: {
  wave: WaveResponse;
  active: boolean;
  onClick: () => void;
}) {
  const tone = WAVE_STATE_TONE[wave.state];
  return (
    <MasterListRow tone={tone} active={active} onClick={onClick} testId={`wave-row-${wave.id}`}>
      <div className="flex items-center justify-between gap-2">
        <span className="numeric text-[13px] font-semibold text-foreground">
          {wave.waveNumber}
        </span>
        <StatusPill label={WAVE_STATE_LABEL[wave.state]} tone={tone} />
      </div>
      <div className="mt-1.5 text-[12.5px] text-foreground/70">
        {wave.totalOrders} orders · {wave.totalLines} lines
      </div>
    </MasterListRow>
  );
}

const TABS: ReadonlyArray<FilterChipOption<BoardTab>> = [
  { value: 'WAVES', label: 'Waves' },
  { value: 'RULES', label: 'Rules' },
];

function WavesBoard() {
  const { hasPermission } = usePermissions();
  const canWrite = hasPermission('fulfillment-write');

  const [tab, setTab] = useState<BoardTab>('WAVES');
  const [filter, setFilter] = useState<WaveFilter>('ALL');
  const [search, setSearch] = useState('');
  const [selectedId, setSelectedId] = useState<number | undefined>();
  const [createOpen, setCreateOpen] = useState(false);

  const { data, isLoading } = useWaves(filter === 'ALL' ? undefined : filter);
  const waves = useMemo(() => {
    const q = search.trim().toLowerCase();
    const content = data?.content ?? [];
    return q ? content.filter((w) => w.waveNumber.toLowerCase().includes(q)) : content;
  }, [data, search]);

  return (
    <div className="space-y-4" data-testid="waves-board">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-4">
          <h1 className="font-display text-2xl font-bold tracking-[-0.02em]">Waves</h1>
          <div className="flex gap-1.5" data-testid="waves-board-tabs">
            <FilterChips options={TABS} value={tab} onChange={setTab} />
          </div>
        </div>
        {tab === 'WAVES' && canWrite && (
          <Button onClick={() => setCreateOpen(true)} data-testid="new-wave-button">
            <Plus className="mr-2 size-4" />
            New wave
          </Button>
        )}
      </div>

      {tab === 'RULES' ? (
        <RulesTab canWrite={canWrite} />
      ) : (
        <MasterDetailLayout
          list={
            <MasterList
              searchValue={search}
              onSearchChange={setSearch}
              searchPlaceholder="Search wave #…"
              chips={<FilterChips options={FILTERS} value={filter} onChange={setFilter} />}
            >
              {isLoading ? (
                <>
                  <Skeleton className="h-[68px] w-full rounded-xl" />
                  <Skeleton className="h-[68px] w-full rounded-xl" />
                </>
              ) : waves.length === 0 ? (
                <p className="px-1 py-6 text-center text-sm text-muted-foreground">
                  No waves match.
                </p>
              ) : (
                waves.map((w) => (
                  <WaveRow
                    key={w.id}
                    wave={w}
                    active={w.id === selectedId}
                    onClick={() => setSelectedId(w.id)}
                  />
                ))
              )}
            </MasterList>
          }
          detail={
            selectedId != null ? (
              <WaveDetail waveId={selectedId} canWrite={canWrite} />
            ) : (
              <DetailEmptyState
                icon={<Layers className="size-8 opacity-40" />}
                message="Select a wave"
              />
            )
          }
        />
      )}

      <CreateWaveDialog open={createOpen} onOpenChange={setCreateOpen} />
    </div>
  );
}

export function WavesPage() {
  const license = useLicense();

  let body: React.ReactNode;
  if (license.isLoading) {
    body = <p className="text-[13px] text-muted-foreground">Loading…</p>;
  } else if (!license.isEntitled('advanced-fulfillment')) {
    body = <LockedWavesPanel />;
  } else {
    body = <WavesBoard />;
  }

  return <div data-testid="waves-page">{body}</div>;
}
