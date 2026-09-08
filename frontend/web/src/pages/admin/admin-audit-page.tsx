import { useState } from 'react';
import { recordTypeMeta, type JournalEntry } from '@/features/insights/use-journals';
import { useAuditLog } from '@/pages/admin/use-audit-log';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';

/**
 * Display is capped client-side -- the demo seeds 1600+ journal rows into a
 * non-virtualized table, so we show only the most recent N (endpoint already
 * returns newest-first) and say so honestly rather than implying "all".
 */
const AUDIT_DISPLAY_LIMIT = 200;

const ALL = 'all';
const INVENTORY_GROUP = 'inventory';
const AUTH_GROUP = 'auth';
/** recordType codes carried over from the pre-SC19 inventory journal. */
const INVENTORY_TYPES = [1, 3, 5, 7];
/** recordType codes added by SC16-18 (LOGIN/LOGOUT/LOGIN_FAILED, Task 6). */
const AUTH_TYPES = [10, 11, 12];

function matchesFilter(entry: JournalEntry, filter: string): boolean {
  if (filter === ALL) return true;
  if (filter === INVENTORY_GROUP) return INVENTORY_TYPES.includes(entry.recordType);
  if (filter === AUTH_GROUP) return AUTH_TYPES.includes(entry.recordType);
  return entry.recordType === Number(filter);
}

/**
 * Admin -> Audit log (B17, extended SC19): the real inventory-journal ledger
 * (`GET /api/v1/journals`, no `location` filter -> every tenant row),
 * newest-first (endpoint order). Reuses the same `JournalEntry` shape and
 * `recordTypeMeta` labels already rendering on Locations' movement timeline
 * and Inventory's ledger — one real audit trail, three views onto it.
 *
 * SC19 adds the auth rows Task 6 started writing (recordType 10/11/12) to
 * that same feed: a record-type filter (All / Inventory / Auth / individual
 * type), an "Activity" column for `activityCode`, and — since `productName`/
 * `productNumber` are always null on an auth row — the source IP reuses that
 * same detail cell rather than adding a column that's empty on every
 * inventory row.
 */
export function AdminAuditPage() {
  const { data, isLoading, isError } = useAuditLog();
  const [filter, setFilter] = useState<string>(ALL);

  const dataTotal = data?.length ?? 0;
  const filtered = data ? data.filter((entry) => matchesFilter(entry, filter)) : [];
  const total = filtered.length;
  const rows = filtered.slice(0, AUDIT_DISPLAY_LIMIT);
  const truncated = total > AUDIT_DISPLAY_LIMIT;

  return (
    <div data-testid="admin-audit-page">
      <div className="mb-5">
        <h1 className="font-display text-2xl font-bold tracking-[-0.02em] text-foreground">
          Audit log
        </h1>
        <p className="mt-1 text-[13px] text-muted-foreground">
          Every inventory-journal event across the tenant, newest first.
        </p>
        {!isLoading && !isError && truncated && (
          <p data-testid="admin-audit-cap-note" className="mt-1 text-[12px] text-muted-foreground">
            Showing the {AUDIT_DISPLAY_LIMIT} most recent entries (of {total}).
          </p>
        )}
      </div>

      <div className="mb-4 flex flex-wrap items-center gap-3">
        <Select value={filter} onValueChange={setFilter}>
          <SelectTrigger className="w-[180px]" data-testid="admin-audit-filter-type">
            <SelectValue placeholder="Event type" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>All events</SelectItem>
            <SelectItem value={INVENTORY_GROUP}>Inventory</SelectItem>
            <SelectItem value={AUTH_GROUP}>Auth</SelectItem>
            {[...INVENTORY_TYPES, ...AUTH_TYPES].map((t) => (
              <SelectItem key={t} value={String(t)}>
                {recordTypeMeta(t).label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {isLoading && (
        <div className="rounded-2xl border border-border bg-card p-8 text-center text-[13px] text-muted-foreground">
          Loading audit log…
        </div>
      )}

      {!isLoading && isError && (
        <div
          data-testid="admin-audit-error"
          className="rounded-2xl border border-border bg-card p-8 text-center text-[13px] text-destructive"
        >
          Couldn&apos;t load the audit log.
        </div>
      )}

      {!isLoading && !isError && dataTotal === 0 && (
        <div className="rounded-2xl border border-border bg-card p-8 text-center text-[13px] text-muted-foreground">
          No journal events yet.
        </div>
      )}

      {!isLoading && !isError && dataTotal > 0 && total === 0 && (
        <div
          data-testid="admin-audit-filter-empty"
          className="rounded-2xl border border-border bg-card p-8 text-center text-[13px] text-muted-foreground"
        >
          No events match this filter.
        </div>
      )}

      {!isLoading && !isError && total > 0 && (
        <div className="rounded-2xl border border-border bg-card">
          <Table data-testid="admin-audit-table">
            <TableHeader>
              <TableRow>
                <TableHead>Date</TableHead>
                <TableHead>Event</TableHead>
                <TableHead>Activity</TableHead>
                <TableHead>Product</TableHead>
                <TableHead>From → To</TableHead>
                <TableHead className="text-right">Amount</TableHead>
                <TableHead>Correlation</TableHead>
                <TableHead>Operator</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((entry, i) => {
                const meta = recordTypeMeta(entry.recordType);
                return (
                  <TableRow key={entry.id ?? i} data-testid={`admin-audit-row-${entry.id ?? i}`}>
                    <TableCell className="numeric whitespace-nowrap text-[12px] text-muted-foreground">
                      {new Date(entry.created).toLocaleString(undefined, {
                        month: 'short',
                        day: 'numeric',
                        hour: '2-digit',
                        minute: '2-digit',
                      })}
                    </TableCell>
                    <TableCell>
                      <span className="flex items-center gap-1.5">
                        <span
                          className="size-2 flex-none rounded-full"
                          style={{ background: meta.dotHex }}
                        />
                        {meta.label}
                      </span>
                    </TableCell>
                    <TableCell className="text-[12px] text-muted-foreground">
                      {entry.activityCode ?? '—'}
                    </TableCell>
                    <TableCell>
                      {entry.productName ??
                        entry.productNumber ??
                        entry.ipAddress ?? <span className="text-muted-foreground">—</span>}
                    </TableCell>
                    <TableCell className="numeric text-[12px]">
                      {entry.fromStorageLocation ?? '—'} → {entry.toStorageLocation ?? '—'}
                    </TableCell>
                    <TableCell className="numeric text-right">{entry.amount ?? '—'}</TableCell>
                    <TableCell className="numeric text-[12px] text-muted-foreground">
                      {entry.correlationId ?? '—'}
                    </TableCell>
                    <TableCell className="text-[12px] text-muted-foreground">
                      {entry.operatorName ?? '—'}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  );
}
