import { Children, useState, type ReactNode } from 'react';
import { Search } from 'lucide-react';
import { cn } from '@/lib/utils';
import { TONE_COLOR, type EntityTone } from '@/components/master-detail/tones';

/**
 * Configurable cap on how many rows the master list renders at once. A large
 * list (hundreds of orders/items) rendered in one flex column both squishes the
 * cards and floods the DOM; capping keeps rows readable and lets the operator
 * pick how many to see. The choice is global + sticky (localStorage).
 */
const ROW_LIMITS = [25, 50, 100, Infinity] as const;
const DEFAULT_ROW_LIMIT = 50;
const ROW_LIMIT_KEY = 'karyo:list-row-limit';

function readStoredLimit(): number {
  if (typeof localStorage === 'undefined') return DEFAULT_ROW_LIMIT;
  const raw = localStorage.getItem(ROW_LIMIT_KEY);
  const v = raw === 'Infinity' ? Infinity : Number(raw);
  return (ROW_LIMITS as readonly number[]).includes(v) ? v : DEFAULT_ROW_LIMIT;
}

export function useRowLimit(): [number, (v: number) => void] {
  const [limit, setLimit] = useState<number>(readStoredLimit);
  const update = (v: number) => {
    setLimit(v);
    try {
      localStorage.setItem(ROW_LIMIT_KEY, String(v));
    } catch {
      /* private mode / storage disabled — keep the in-memory value */
    }
  };
  return [limit, update];
}

/**
 * Reusable master–detail skeleton (v3 design handoff). Every entity page is a
 * left list (search + filter chips, ~352px, selected row gets a 3px status
 * rail) + a right detail that is a *workspace*, not a form. Orders / Locations
 * / Items / Inventory all compose these primitives; reuse for Picks/ASNs/etc.
 * Tone constants live in ./tones (keeps this a components-only module).
 */

/** Two-pane layout: scrollable list (left) + scrollable detail workspace (right). */
export function MasterDetailLayout({
  list,
  detail,
}: {
  list: ReactNode;
  detail: ReactNode;
}) {
  return (
    <div className="flex h-[calc(100vh-7.5rem)] flex-col gap-5 md:flex-row">
      <div className="flex w-full flex-none flex-col overflow-hidden md:max-w-[352px]">
        {list}
      </div>
      <div className="min-w-0 flex-1 overflow-y-auto rounded-2xl">{detail}</div>
    </div>
  );
}

/** List pane: search box + filter chips + scrollable rows. */
export function MasterList({
  searchValue,
  onSearchChange,
  searchPlaceholder = 'Search…',
  chips,
  footer,
  children,
}: {
  searchValue: string;
  onSearchChange: (v: string) => void;
  searchPlaceholder?: string;
  chips?: ReactNode;
  footer?: ReactNode;
  children: ReactNode;
}) {
  const [limit, setLimit] = useRowLimit();
  const rows = Children.toArray(children);
  const total = rows.length;
  const visible = limit === Infinity ? rows : rows.slice(0, limit);
  const truncated = total > limit;

  return (
    <div className="flex h-full flex-col gap-3">
      <div className="relative flex-none">
        <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
        <input
          value={searchValue}
          onChange={(e) => onSearchChange(e.target.value)}
          placeholder={searchPlaceholder}
          className="h-10 w-full rounded-xl border border-border bg-card pl-9 pr-3 text-sm text-foreground placeholder:text-muted-foreground focus-visible:border-ring focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring/40"
        />
      </div>
      {chips && <div className="flex flex-none flex-wrap gap-1.5">{chips}</div>}
      <div className="flex flex-1 flex-col gap-2 overflow-y-auto pr-1">{visible}</div>
      {total > ROW_LIMITS[0] && (
        <div
          className="flex flex-none items-center justify-between gap-2 border-t border-border pt-2 text-[11.5px] text-muted-foreground"
          data-testid="master-list-rowcap"
        >
          <span className="numeric">
            {truncated ? (
              <>
                Showing <span className="font-semibold text-foreground">{visible.length}</span> of{' '}
                {total}
              </>
            ) : (
              <>
                <span className="font-semibold text-foreground">{total}</span> shown
              </>
            )}
          </span>
          <div className="flex items-center gap-1">
            <span className="mr-0.5 uppercase tracking-wide text-[10px]">Rows</span>
            {ROW_LIMITS.map((l) => (
              <button
                key={String(l)}
                type="button"
                onClick={() => setLimit(l)}
                className={cn(
                  'rounded-md px-2 py-0.5 text-[11px] font-medium transition-colors',
                  limit === l
                    ? 'bg-primary font-semibold text-primary-foreground'
                    : 'bg-card text-muted-foreground hover:text-foreground',
                )}
              >
                {l === Infinity ? 'All' : l}
              </button>
            ))}
          </div>
        </div>
      )}
      {footer}
    </div>
  );
}

export interface FilterChipOption<T extends string> {
  value: T;
  label: string;
}

/** Segmented filter chips (e.g. All / Picking / Exception / Ready). */
export function FilterChips<T extends string>({
  options,
  value,
  onChange,
}: {
  options: ReadonlyArray<FilterChipOption<T>>;
  value: T;
  onChange: (v: T) => void;
}) {
  return (
    <>
      {options.map((opt) => (
        <button
          key={opt.value}
          type="button"
          onClick={() => onChange(opt.value)}
          className={cn(
            'rounded-lg px-3 py-1.5 text-[12.5px] font-medium transition-colors',
            value === opt.value
              ? 'bg-primary font-semibold text-primary-foreground'
              : 'bg-card text-muted-foreground hover:text-foreground',
          )}
        >
          {opt.label}
        </button>
      ))}
    </>
  );
}

/** A selectable list row with a status-colored left rail. */
export function MasterListRow({
  tone,
  active,
  onClick,
  children,
  testId,
}: {
  tone: EntityTone;
  active: boolean;
  onClick: () => void;
  children: ReactNode;
  /** Optional data-testid on the row button (e2e hooks). */
  testId?: string;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      data-active={active}
      data-testid={testId}
      className={cn(
        // shrink-0: keep each card at its natural height so a long list scrolls
        // instead of the flex column squishing every row to nothing.
        'relative w-full shrink-0 overflow-hidden rounded-xl border bg-card p-3.5 pl-4 text-left transition-colors',
        active ? 'border-primary/40 bg-accent' : 'border-border hover:bg-accent/60',
      )}
    >
      <span
        aria-hidden
        className="absolute left-0 top-0 h-full w-[3px]"
        style={{ background: TONE_COLOR[tone] }}
      />
      {children}
    </button>
  );
}

/** Placeholder shown in the detail pane when nothing is selected. */
export function DetailEmptyState({
  icon,
  message,
}: {
  icon?: ReactNode;
  message: string;
}) {
  return (
    <div className="flex h-full min-h-[300px] flex-col items-center justify-center gap-3 rounded-2xl border border-border bg-card text-muted-foreground">
      {icon}
      <p className="text-sm">{message}</p>
    </div>
  );
}
