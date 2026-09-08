import { cn } from '@/lib/utils';
import { useOccupancy } from '@/features/insights/use-occupancy';
import type { OccupancyState, OccupancyZone } from '@/types/insights';

const STATE_CLASS: Record<OccupancyState, string> = {
  occupied: 'bg-primary/80 border-primary/40',
  empty: 'bg-muted/40 border-border',
  locked: 'bg-[rgba(224,164,90,0.5)] border-[rgba(224,164,90,0.5)]',
};

const STATE_LABEL: Record<OccupancyState, string> = {
  occupied: 'Occupied',
  empty: 'Empty',
  locked: 'Locked',
};

const pctText = (p: number) => `${Math.round(p * 100)}%`;

/** Honest-data doctrine: never fabricate a percentage — null utilization renders an em-dash. */
const utilizationText = (u: number | null | undefined) => (u == null ? '—' : `${Math.round(u * 100)}%`);

export function OccupancyPage() {
  const { data, isLoading, isError } = useOccupancy();

  return (
    <div data-testid="occupancy-page">
      <div className="mb-5">
        <h1 className="font-display text-2xl font-bold tracking-[-0.02em] text-foreground">
          Occupancy
        </h1>
        <p className="mt-1 text-[13px] text-muted-foreground">
          Storage occupancy by zone — occupied, empty, and locked locations.
        </p>
      </div>

      {isLoading && (
        <div className="grid gap-4 sm:grid-cols-2">
          {[0, 1].map((i) => (
            <div
              key={i}
              className="h-40 animate-pulse rounded-2xl border border-border bg-card"
            />
          ))}
        </div>
      )}

      {isError && (
        <div className="rounded-2xl border border-border bg-card p-4 text-[13px] text-destructive">
          Couldn&apos;t load occupancy.
        </div>
      )}

      {data && (
        <>
          {/* warehouse rollups */}
          <div className="mb-5 grid gap-4 sm:grid-cols-2">
            <div className="rounded-2xl border border-border bg-card px-[18px] py-[15px]">
              <div className="text-[10px] font-semibold uppercase tracking-[0.12em] text-muted-foreground/70">
                Warehouse occupancy
              </div>
              <div className="mt-2 flex items-center gap-3">
                <div className="numeric text-[23px] font-bold text-foreground">
                  {pctText(data.totals.pct)}
                </div>
                <div className="numeric text-[12px] text-muted-foreground">
                  {data.totals.occupied} / {data.totals.total} locations
                </div>
              </div>
              <Bar pct={data.totals.pct} />
            </div>

            {/* SC21: slot utilization from storage_locations.capacity (nullable — honest —, em-dash when unset) */}
            <div className="rounded-2xl border border-border bg-card px-[18px] py-[15px]">
              <div className="text-[10px] font-semibold uppercase tracking-[0.12em] text-muted-foreground/70">
                Slot utilization
              </div>
              <div className="mt-2 flex items-center gap-3">
                <div className="numeric text-[23px] font-bold text-foreground">
                  {utilizationText(data.totals.utilization)}
                </div>
              </div>
              <p className="mt-1 numeric text-[12px] text-muted-foreground">
                {data.totals.usedSlots ?? 0}/{data.totals.capacitySlots ?? 0} slots · capacity set on{' '}
                {data.totals.locationsWithCapacity ?? 0}/{data.totals.total} locations
              </p>
              <Bar pct={data.totals.utilization ?? 0} />
            </div>
          </div>

          {data.zones.length === 0 && !data.unzoned ? (
            <div className="rounded-2xl border border-border bg-card p-8 text-center text-[13px] text-muted-foreground">
              No locations yet — add locations to see occupancy.
            </div>
          ) : (
            <div className="grid gap-4 sm:grid-cols-2">
              {data.zones.map((z) => (
                <ZoneCard key={z.zoneId ?? 'unzoned-zone'} zone={z} />
              ))}
              {data.unzoned && <ZoneCard key="unzoned" zone={data.unzoned} />}
            </div>
          )}

          {/* legend */}
          <div className="mt-5 flex items-center gap-4 border-t border-border pt-3.5">
            {(['occupied', 'empty', 'locked'] as OccupancyState[]).map((s) => (
              <span key={s} className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
                <span className={cn('size-3 rounded-[3px] border', STATE_CLASS[s])} />
                {STATE_LABEL[s]}
              </span>
            ))}
          </div>
        </>
      )}
    </div>
  );
}

function ZoneCard({ zone }: { zone: OccupancyZone }) {
  return (
    <section className="rounded-2xl border border-border bg-card p-[18px]">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="m-0 text-[15px] font-semibold text-foreground">{zone.zoneName}</h2>
        <span className="numeric text-[12px] font-bold text-foreground">
          {pctText(zone.pct)}
          <span className="ml-1 font-normal text-muted-foreground">
            {zone.occupied}/{zone.total}
          </span>
          {zone.capacitySlots != null && (
            <span className="ml-2 font-normal text-muted-foreground">
              {zone.usedSlots ?? 0}/{zone.capacitySlots} slots
            </span>
          )}
        </span>
      </div>
      <Bar pct={zone.pct} />
      <div className="mt-3 flex flex-wrap gap-1.5">
        {zone.locations.map((c) => (
          <span
            key={c.id}
            title={`${c.name} — ${STATE_LABEL[c.state]}`}
            className={cn('size-6 rounded-[4px] border', STATE_CLASS[c.state])}
          />
        ))}
      </div>
    </section>
  );
}

function Bar({ pct }: { pct: number }) {
  return (
    <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-muted/40">
      <div
        className="h-full rounded-full bg-primary"
        style={{ width: `${Math.round(pct * 100)}%` }}
      />
    </div>
  );
}
