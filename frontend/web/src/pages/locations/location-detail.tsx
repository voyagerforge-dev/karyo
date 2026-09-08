import { ArrowDownToLine, ClipboardCheck, Ban, Pencil } from 'lucide-react';
import { toast } from 'sonner';
import { cn } from '@/lib/utils';
import { TONE_COLOR } from '@/components/master-detail/tones';
import { saveZpl, archiveDocument } from '@/lib/document-actions';
import { useJournals, recordTypeMeta } from '@/features/insights/use-journals';
import { occTone, type LocationView } from './location-derive';
import type { ContentRow } from './use-location-contents';

const STATUS_STYLE: Record<LocationView['status'], { color: string; bg: string }> = {
  Active: { color: 'var(--acc-color)', bg: 'var(--acc-soft)' },
  Blocked: { color: 'var(--destructive)', bg: 'var(--error)' },
  Empty: { color: 'var(--muted-foreground)', bg: 'var(--accent)' },
};

const CONTENT_STATUS_STYLE: Record<ContentRow['status'], { color: string; bg: string }> = {
  Pickable: { color: 'var(--acc-color)', bg: 'var(--acc-soft)' },
  Reserved: { color: 'var(--warning-foreground)', bg: 'var(--warning)' },
  'QA hold': { color: 'var(--destructive)', bg: 'var(--error)' },
};

/** SVG stroke-dashoffset gauge, colored by fill. */
function OccupancyRing({ view, used }: { view: LocationView; used: number }) {
  const tone = occTone(view.occPct, view.status);
  const color = TONE_COLOR[tone];
  const radius = 52;
  const circumference = 2 * Math.PI * radius;
  const offset = circumference * (1 - view.occPct / 100);

  return (
    <section className="flex w-full flex-none flex-col items-center justify-center rounded-2xl border border-border bg-card p-5 md:w-[240px]">
      <div className="relative size-[140px]">
        <svg width="140" height="140" viewBox="0 0 140 140" aria-hidden>
          <circle cx="70" cy="70" r={radius} fill="none" stroke="var(--background)" strokeWidth="14" />
          <circle
            cx="70"
            cy="70"
            r={radius}
            fill="none"
            stroke={color}
            strokeWidth="14"
            strokeLinecap="round"
            strokeDasharray={circumference.toFixed(1)}
            strokeDashoffset={offset.toFixed(1)}
            transform="rotate(-90 70 70)"
          />
        </svg>
        <div className="absolute inset-0 flex flex-col items-center justify-center">
          <span className="numeric text-[30px] font-extrabold leading-none text-foreground">
            {view.occPct}
            <span className="text-[15px] text-muted-foreground/80">%</span>
          </span>
          <span className="numeric mt-1 text-[10px] tracking-[0.08em] text-muted-foreground/70">OCCUPIED</span>
        </div>
      </div>
      <div className="mt-[18px] flex gap-[18px]">
        <div className="text-center">
          <div className="numeric text-[16px] font-bold text-foreground">{used}</div>
          <div className="mt-0.5 text-[11px] text-muted-foreground/70">used</div>
        </div>
        <div className="text-center">
          <div className="numeric text-[16px] font-bold text-muted-foreground">{view.capacity ?? '—'}</div>
          <div className="mt-0.5 text-[11px] text-muted-foreground/70">capacity (slots)</div>
        </div>
      </div>
    </section>
  );
}

function ConstraintCell({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div>
      <div className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground/70">{label}</div>
      <div className={cn('mt-[5px] text-[14px] font-semibold text-foreground/90', mono && 'numeric')}>{value}</div>
    </div>
  );
}

interface LocationDetailProps {
  view: LocationView;
  contents: ContentRow[];
  used: number;
  canWrite: boolean;
  onBlock: () => void;
  onEdit: () => void;
}

export function LocationDetail({ view, contents, used, canWrite, onBlock, onEdit }: LocationDetailProps) {
  const status = STATUS_STYLE[view.status];
  const blocked = view.lockType > 0;
  const { data: movements, isLoading: movementsLoading } = useJournals({ location: view.name });

  return (
    <div className="px-1 py-1 md:px-2">
      {/* Header */}
      <div className="mb-5 flex flex-col items-start justify-between gap-3 sm:flex-row">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="numeric m-0 text-[26px] font-bold tracking-[-0.01em] text-foreground">{view.code}</h1>
            <span
              className="rounded-full px-2.5 py-[3px] text-[11.5px] font-bold"
              style={{ color: status.color, background: status.bg }}
            >
              {view.status}
            </span>
            {view.excludedFromPutaway && (
              <span
                data-testid="excluded-from-putaway-badge"
                className="rounded-full px-2.5 py-[3px] text-[11.5px] font-bold"
                style={{ color: 'var(--warning-foreground)', background: 'var(--warning)' }}
              >
                Excluded from putaway
              </span>
            )}
            {view.isClearing && (
              <span
                data-testid="clearing-badge"
                className="rounded-full px-2.5 py-[3px] text-[11.5px] font-bold"
                style={{ color: 'var(--acc-color)', background: 'var(--acc-soft)' }}
              >
                Clearing
              </span>
            )}
          </div>
          <p className="mt-[7px] text-[13.5px] text-foreground/70">
            {view.zoneLabel} · {view.typeName} · pick sequence{' '}
            <span className="numeric">{view.pickSequence}</span>
          </p>
        </div>
        <div className="flex gap-2">
          {/* Read-only document — no write-perm gate, unlike the three
              action buttons below it. */}
          <button
            type="button"
            data-testid="doc-label-btn"
            onClick={() => saveZpl(`/api/v1/locations/${view.id}/label.zpl`, `location-${view.name}.zpl`)}
            className="h-9 rounded-[9px] border border-border bg-card px-3 text-[13px] font-medium text-foreground/85 transition-colors hover:bg-accent"
          >
            Label (ZPL)
          </button>
          <button
            type="button"
            data-testid="doc-label-archive-btn"
            onClick={() => archiveDocument(`/api/v1/locations/${view.id}/label.zpl`)}
            className="h-9 rounded-[9px] border border-border bg-card px-3 text-[13px] font-medium text-foreground/85 transition-colors hover:bg-accent"
          >
            Archive
          </button>
          <button
            type="button"
            data-testid="location-edit-btn"
            disabled={!canWrite}
            onClick={onEdit}
            className="h-9 rounded-[9px] border border-border bg-card px-3 text-[13px] font-medium text-foreground/85 transition-colors hover:bg-accent disabled:cursor-not-allowed disabled:opacity-40"
          >
            <Pencil className="mr-1.5 inline size-4" />
            Edit
          </button>
          <button
            type="button"
            disabled={!canWrite}
            onClick={() => toast.success(`Replenishment queued for ${view.code}`)}
            className="h-9 rounded-[9px] bg-primary px-3.5 text-[13px] font-bold text-primary-foreground transition-opacity hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40"
          >
            <ArrowDownToLine className="mr-1.5 inline size-4" />
            Replenish
          </button>
          <button
            type="button"
            disabled={!canWrite}
            onClick={() => toast.success(`Cycle count scheduled for ${view.code}`)}
            className="h-9 rounded-[9px] border border-border bg-card px-3 text-[13px] font-medium text-foreground/85 transition-colors hover:bg-accent disabled:cursor-not-allowed disabled:opacity-40"
          >
            <ClipboardCheck className="mr-1.5 inline size-4" />
            Cycle count
          </button>
          <button
            type="button"
            disabled={!canWrite}
            onClick={onBlock}
            className="h-9 rounded-[9px] border border-destructive/20 bg-card px-3 text-[13px] font-medium text-destructive transition-colors hover:bg-accent disabled:cursor-not-allowed disabled:opacity-40"
          >
            <Ban className="mr-1.5 inline size-4" />
            {blocked ? 'Unblock' : 'Block'}
          </button>
        </div>
      </div>

      {/* Ring + constraints */}
      <div className="mb-4 flex flex-col items-stretch gap-4 md:flex-row">
        <OccupancyRing view={view} used={used} />
        <section className="min-w-0 flex-1 rounded-2xl border border-border bg-card p-5">
          <h2 className="m-0 mb-4 text-[14px] font-semibold text-foreground">Constraints</h2>
          <div className="grid grid-cols-1 gap-x-[18px] gap-y-[14px] sm:grid-cols-2">
            <ConstraintCell label="Dimensions" value={view.dimensions} mono />
            <ConstraintCell label="Max weight" value={view.maxWeight} mono />
            <ConstraintCell label="Storage class" value={view.storageClass} />
            <ConstraintCell label="Temperature" value={view.temperature} />
            <ConstraintCell label="Replen rule" value={view.replenRule} />
            <ConstraintCell label="Last counted" value={view.lastCounted} mono />
            <ConstraintCell label="PLC code" value={view.plcCode} mono />
          </div>
          {view.note && (
            <div
              className="mt-4 flex items-start gap-2 rounded-xl border bg-background p-[11px]"
              style={{
                borderColor:
                  view.note.tone === 'red'
                    ? 'color-mix(in oklab, var(--destructive) 30%, transparent)'
                    : 'color-mix(in oklab, var(--warning-foreground) 30%, transparent)',
              }}
            >
              <span
                aria-hidden
                className="mt-1 size-[7px] flex-none rounded-full"
                style={{ background: TONE_COLOR[view.note.tone] }}
              />
              <span className="text-[12px] leading-[1.45] text-foreground/75">{view.note.text}</span>
            </div>
          )}
        </section>
      </div>

      {/* Stored here */}
      <section className="mb-4 overflow-hidden rounded-2xl border border-border bg-card">
        <div className="flex items-center justify-between border-b border-border px-[18px] py-3.5">
          <h2 className="m-0 text-[14px] font-semibold text-foreground">Stored here</h2>
          <span className="numeric text-[10.5px] text-muted-foreground/70">
            {contents.length} SKU · {used} UNITS
          </span>
        </div>
        {contents.length === 0 ? (
          <div className="p-[34px] text-center text-[13px] text-muted-foreground/70">This location is empty.</div>
        ) : (
          contents.map((c, i) => {
            const st = CONTENT_STATUS_STYLE[c.status];
            return (
              <div
                key={`${c.sku}-${c.lot}-${i}`}
                className="grid grid-cols-[1.6fr_1fr_100px_90px] items-center gap-3 border-b border-border px-[18px] py-3 last:border-b-0"
              >
                <div className="min-w-0">
                  <div className="numeric text-[12.5px] font-semibold text-foreground">{c.sku}</div>
                  <div className="mt-0.5 truncate text-[12px] text-muted-foreground">{c.desc}</div>
                </div>
                <div className="numeric text-[12px] text-muted-foreground">{c.lot}</div>
                <div className="numeric text-right text-[13px] font-bold text-foreground">{c.qty}</div>
                <div className="text-right">
                  <span
                    className="rounded-full px-[9px] py-0.5 text-[11px] font-bold"
                    style={{ color: st.color, background: st.bg }}
                  >
                    {c.status}
                  </span>
                </div>
              </div>
            );
          })
        )}
      </section>

      {/* Recent movements — real inventory-journal rows for this location. */}
      <section className="rounded-2xl border border-border bg-card px-5 py-[18px]">
        <h2 className="m-0 mb-4 text-[14px] font-semibold text-foreground">Recent movements</h2>
        {movementsLoading && movements.length === 0 ? (
          <p className="py-6 text-center text-[13px] text-muted-foreground/70">Loading movements…</p>
        ) : movements.length === 0 ? (
          <p className="py-6 text-center text-[13px] text-muted-foreground/70">No recent movements yet.</p>
        ) : (
          <div className="flex flex-col">
            {movements.map((m, i) => {
              const meta = recordTypeMeta(m.recordType);
              return (
                <div key={i} className="flex gap-[13px] pb-3.5 last:pb-0">
                  <div className="flex flex-none flex-col items-center">
                    <span className="mt-[3px] size-[9px] rounded-full" style={{ background: meta.dotHex }} />
                    {i < movements.length - 1 && <span className="mt-[3px] w-0.5 flex-1 bg-muted" />}
                  </div>
                  <div className="flex flex-1 justify-between gap-3">
                    <div className="text-[12.5px] text-foreground/85">
                      {meta.label} {m.amount ?? 0}
                      {m.correlationId ? <span className="text-muted-foreground"> · {m.correlationId}</span> : null}
                    </div>
                    <div className="numeric whitespace-nowrap text-[10.5px] text-muted-foreground/70">
                      {new Date(m.created).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </section>
    </div>
  );
}
