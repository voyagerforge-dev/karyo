import { useState } from 'react';
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
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { useCountOrderEntry, useSubmitCount, useUnitLoadMissing, useLocationEmpty } from './use-cycle-count';
import type { CountEntryLine } from '@/types/cycle-count';

interface CountEntryFormProps {
  orderId: number;
  onDone: () => void;
}

interface LineGroup {
  key: string;
  unitLoadId: number | null;
  label: string;
  lines: CountEntryLine[];
}

/** Groups lines by unit load (St4) -- a null unitLoadId (pre-migration line, or genuinely
 *  loose stock) falls under the single "Loose stock" bucket. */
function groupByUnitLoad(lines: CountEntryLine[]): LineGroup[] {
  const groups = new Map<string, LineGroup>();
  for (const line of lines) {
    const key = line.unitLoadId != null ? `ul-${line.unitLoadId}` : 'loose';
    let group = groups.get(key);
    if (!group) {
      group = {
        key,
        unitLoadId: line.unitLoadId,
        label: line.unitLoadLabel ?? 'Loose stock',
        lines: [],
      };
      groups.set(key, group);
    }
    group.lines.push(line);
  }
  return Array.from(groups.values());
}

/**
 * Blind count-entry form.
 *
 * Fetches the blind (?view=entry) projection of a count order — no planned amounts shown
 * (warehouse operator counts what they see, not what's expected). Lines are grouped by unit
 * load (St4); a group with a real unit load id gets a "Unit load missing" action that zeroes
 * every still-open line in that group (POST .../unit-loads/missing) — those lines then lock
 * (no further input, `line.counted`) while the rest of the order stays open for counting. One
 * numeric input per un-locked line; on submit POSTs to /count.
 *
 * "Location is empty" (St3, POST .../location-empty) is the confirm-empty op: for a zero-line
 * order (nothing was on record for this location at snapshot time) it's the ONLY way to close
 * the order out — there is nothing to submit, so no lines/table render at all, just an
 * empty-state and the button (previously this rendered a useless always-enabled Submit button
 * over an empty table, a silent-no-op trap on the backend closed alongside this button). For an
 * order that does have lines it's an alternative to filling in the whole blind count when the
 * operator finds the location genuinely empty on arrival — every still-open line is zeroed for
 * manager review, same as a real 0 count.
 */
export function CountEntryForm({ orderId, onDone }: CountEntryFormProps) {
  const { data: entry, isLoading } = useCountOrderEntry(orderId);
  const submit = useSubmitCount(orderId);
  const unitLoadMissing = useUnitLoadMissing(orderId);
  const locationEmpty = useLocationEmpty(orderId);

  // Map lineId → counted amount string (empty = not entered yet)
  const [amounts, setAmounts] = useState<Record<number, string>>({});
  const [groupToReport, setGroupToReport] = useState<LineGroup | undefined>();
  const [confirmLocationEmpty, setConfirmLocationEmpty] = useState(false);

  if (isLoading) {
    return <p className="py-6 text-center text-sm text-muted-foreground">Loading count order…</p>;
  }

  if (!entry) {
    return <p className="py-6 text-center text-sm text-muted-foreground">Order not found.</p>;
  }

  // NOTE: CountEntryView (blind view) never carried an order-level `state` field on the
  // backend -- an "already submitted" guard here would check an undefined property and be
  // dead code. Removed rather than kept as a fabricated field on the FE type.

  const isZeroLine = entry.lines.length === 0;
  const groups = groupByUnitLoad(entry.lines);
  const openLines = entry.lines.filter((l) => !l.counted);

  const handleChange = (lineId: number, value: string) => {
    setAmounts((prev) => ({ ...prev, [lineId]: value }));
  };

  const allEntered = openLines.every((l) => {
    const v = amounts[l.lineId];
    return v !== undefined && v !== '' && !isNaN(Number(v)) && Number(v) >= 0;
  });

  const handleSubmit = () => {
    // Only still-open lines need an input -- a line already counted (zeroed by the
    // missing-op) is left for the backend to pick up as-is (see submitCount's KDoc).
    const lines = openLines.map((l) => ({
      lineId: l.lineId,
      countedAmount: Number(amounts[l.lineId] ?? 0),
    }));
    submit.mutate(
      { lines },
      {
        onSuccess: () => {
          onDone();
        },
      },
    );
  };

  const handleLocationEmpty = () => {
    locationEmpty.mutate(undefined, {
      onSuccess: () => {
        setConfirmLocationEmpty(false);
        onDone();
      },
    });
  };

  const isPending = submit.isPending || locationEmpty.isPending;

  return (
    <div className="space-y-4">
      <div>
        <p className="text-sm font-medium">{entry.locationName}</p>
        <p className="text-xs text-muted-foreground">
          {isZeroLine
            ? 'No stock was on record for this location when this count was generated.'
            : 'Enter the quantity you count for each item. Planned amounts are not shown (blind count).'}
        </p>
      </div>

      {isZeroLine ? (
        <div
          className="rounded-md border border-dashed py-8 text-center text-sm text-muted-foreground"
          data-testid="zero-line-empty-state"
        >
          Nothing to count here — confirm the location is empty below.
        </div>
      ) : (
      // Stable testid on the wrapper (not the per-group tables) -- pre-St4 tests key off
      // this single id to detect "the entry form is open"; per-group ids live one level
      // down (`ul-group-{key}`).
      <div className="space-y-4" data-testid="count-entry-table">
        {groups.map((group) => {
          const groupHasOpenLines = group.lines.some((l) => !l.counted);
          return (
            <div key={group.key} className="space-y-2" data-testid={`ul-group-${group.key}`}>
              <div className="flex items-center justify-between">
                <p className="text-xs font-medium text-muted-foreground">{group.label}</p>
                {group.unitLoadId != null && groupHasOpenLines && (
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => setGroupToReport(group)}
                    data-testid={`unit-load-missing-${group.unitLoadId}`}
                  >
                    Unit load missing
                  </Button>
                )}
              </div>

              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Item</TableHead>
                    <TableHead>Lot</TableHead>
                    <TableHead>Serial</TableHead>
                    <TableHead className="w-32 text-right">Counted qty</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {group.lines.map((line) => (
                    <TableRow key={line.lineId} data-testid={`entry-line-${line.lineId}`}>
                      <TableCell className="font-mono text-[13px]">{line.itemDataNumber}</TableCell>
                      <TableCell className="text-[13px]">{line.lotNumber ?? '—'}</TableCell>
                      <TableCell className="text-[13px]">{line.serialNumber ?? '—'}</TableCell>
                      <TableCell className="text-right">
                        <Label htmlFor={`qty-${line.lineId}`} className="sr-only">
                          Counted quantity for {line.itemDataNumber}
                        </Label>
                        <Input
                          id={`qty-${line.lineId}`}
                          type="number"
                          min={0}
                          step="any"
                          placeholder="0"
                          className="w-24 text-right tabular-nums"
                          value={line.counted ? '0' : (amounts[line.lineId] ?? '')}
                          disabled={line.counted}
                          onChange={(e) => handleChange(line.lineId, e.target.value)}
                          data-testid={`qty-input-${line.lineId}`}
                        />
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          );
        })}
      </div>
      )}

      <div className="flex justify-end gap-2">
        <Button variant="outline" onClick={onDone} disabled={isPending}>
          Cancel
        </Button>
        <Button
          variant="outline"
          onClick={() => setConfirmLocationEmpty(true)}
          disabled={isPending}
          data-testid="location-empty-button"
        >
          Location is empty
        </Button>
        {!isZeroLine && (
          <Button
            onClick={handleSubmit}
            disabled={!allEntered || submit.isPending}
            data-testid="submit-count-button"
          >
            Submit count
          </Button>
        )}
      </div>

      <AlertDialog
        open={groupToReport != null}
        onOpenChange={(open) => !open && setGroupToReport(undefined)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Report this unit load missing?</AlertDialogTitle>
            <AlertDialogDescription>
              &quot;{groupToReport?.label}&quot; will be counted at 0 for every item still open
              in this group. A manager reviews the resulting discrepancy after you submit the
              rest of the count.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Keep counting</AlertDialogCancel>
            <AlertDialogAction
              data-testid="confirm-unit-load-missing"
              onClick={() => {
                if (groupToReport?.unitLoadId == null) return;
                unitLoadMissing.mutate(groupToReport.unitLoadId, {
                  onSuccess: () => setGroupToReport(undefined),
                });
              }}
            >
              Unit load missing
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <AlertDialog
        open={confirmLocationEmpty}
        onOpenChange={(open) => !open && setConfirmLocationEmpty(false)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Confirm the location is empty?</AlertDialogTitle>
            <AlertDialogDescription>
              {isZeroLine
                ? 'This finishes the count immediately -- no stock was expected here.'
                : 'Every remaining line will be counted at 0. A manager reviews the resulting discrepancy.'}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Keep counting</AlertDialogCancel>
            <AlertDialogAction data-testid="confirm-location-empty" onClick={handleLocationEmpty}>
              Location is empty
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
