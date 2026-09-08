import { RefreshCw } from 'lucide-react';
import { SectionCard } from '@/components/control/section-card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { usePermissions } from '@/hooks/use-permissions';
import {
  useReplenishmentNeeds,
  useScanReplenishment,
} from '@/features/replenishment/use-replenishment';
import type { GeneratedTask, ReplenishmentShortfall } from '@/types/replenishment';

const EMPTY = '—';

/**
 * A row is area-level (Mode 2, R12b) when it carries an [itemDataAreaId] --
 * mutually exclusive with fixAssignmentId, see the GeneratedTask/
 * ReplenishmentShortfall KDoc in ReplenishmentDtos.kt.
 */
function isAreaRow(itemDataAreaId: number | null): boolean {
  return itemDataAreaId != null;
}

/**
 * A shortfall's [locationName] is a synthetic "AREA-{id}" placeholder ONLY
 * for the area case (ReplenishmentService.areaShortfall has no real
 * destination to report). Generated area tasks always carry a real
 * destination location name (chooseDestination), so this only reshapes the
 * synthetic id form -- never a real name -- rendering it honestly rather
 * than passing the internal "AREA-7" token straight through.
 */
function locationLabel(locationName: string, itemDataAreaId: number | null): string {
  if (itemDataAreaId != null && locationName === `AREA-${itemDataAreaId}`) {
    return `Area ${itemDataAreaId}`;
  }
  return locationName;
}

function RowTypeBadge({ itemDataAreaId }: { itemDataAreaId: number | null }) {
  return isAreaRow(itemDataAreaId) ? (
    <Badge variant="outline">Area</Badge>
  ) : (
    <Badge variant="secondary">Fix face</Badge>
  );
}

/**
 * Last-scan results (Task 7): the /scan response's `generated`/`shortfalls`
 * rows -- fix-face (Mode 1) AND area-level (Mode 2, R12b) -- were computed by
 * every prior scan but never rendered beyond a toast count. Rendered here
 * honestly: no top-up amount column, because GeneratedTask carries none
 * (ReplenishmentService.topUpAmount feeds ReplenishmentTaskCommand only, it
 * is never echoed back in the response DTO).
 */
function ScanResultsSection({
  generated,
  shortfalls,
}: {
  generated: GeneratedTask[];
  shortfalls: ReplenishmentShortfall[];
}) {
  if (generated.length === 0 && shortfalls.length === 0) return null;

  return (
    <div className="space-y-4" data-testid="replenishment-scan-results">
      {generated.length > 0 && (
        <div>
          <h3 className="mb-2 text-sm font-semibold text-foreground">
            Generated tasks ({generated.length})
          </h3>
          <Table data-testid="replenishment-generated-table">
            <TableHeader>
              <TableRow>
                <TableHead>Type</TableHead>
                <TableHead>Task</TableHead>
                <TableHead>Location</TableHead>
                <TableHead>SKU</TableHead>
                <TableHead>Source UL</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {generated.map((t) => (
                <TableRow key={t.taskId} data-testid={`replenishment-generated-${t.taskId}`}>
                  <TableCell>
                    <RowTypeBadge itemDataAreaId={t.itemDataAreaId} />
                  </TableCell>
                  <TableCell className="font-mono text-[13px]">{t.orderNumber}</TableCell>
                  <TableCell className="font-mono text-[13px]">
                    {locationLabel(t.locationName, t.itemDataAreaId)}
                  </TableCell>
                  <TableCell className="font-mono text-[13px]">
                    {t.itemDataNumber ?? EMPTY}
                  </TableCell>
                  <TableCell className="font-mono text-[13px]">{t.unitLoadId}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {shortfalls.length > 0 && (
        <div>
          <h3 className="mb-2 text-sm font-semibold text-foreground">
            Shortfalls ({shortfalls.length})
          </h3>
          <Table data-testid="replenishment-shortfalls-table">
            <TableHeader>
              <TableRow>
                <TableHead>Type</TableHead>
                <TableHead>Location</TableHead>
                <TableHead>SKU</TableHead>
                <TableHead className="text-right">Current</TableHead>
                <TableHead className="text-right">Min</TableHead>
                <TableHead>Reason</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {shortfalls.map((sf, i) => (
                <TableRow key={`${sf.fixAssignmentId ?? sf.itemDataAreaId}-${i}`} data-testid={`replenishment-shortfall-${i}`}>
                  <TableCell>
                    <RowTypeBadge itemDataAreaId={sf.itemDataAreaId} />
                  </TableCell>
                  <TableCell className="font-mono text-[13px]">
                    {locationLabel(sf.locationName, sf.itemDataAreaId)}
                  </TableCell>
                  <TableCell className="font-mono text-[13px]">
                    {sf.itemDataNumber ?? EMPTY}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{sf.currentAmount}</TableCell>
                  <TableCell className="text-right tabular-nums">
                    {sf.minAmount ?? EMPTY}
                  </TableCell>
                  <TableCell className="font-mono text-[13px]">{sf.reason}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  );
}

/**
 * REPLENISH-chip needs section (P3 Task 4): rendered in the tasks-page detail
 * slot when the REPLENISH filter chip is active and nothing is selected --
 * mirrors the retired standalone Replenishment page's needs table + scan
 * action (replenishment-columns.tsx) so the unified Tasks inbox surfaces
 * "what needs replenishing" without a separate route.
 *
 * Task 7: also renders the last scan's `generated`/`shortfalls` rows (both
 * fix-face and area-level, R12b) -- these were computed by every scan but
 * previously only summarized in a toast. The needs table above stays
 * fix-face-only, matching what GET /replenishment/needs actually returns
 * (there is no area-level needs endpoint -- area deficiencies only surface
 * as a byproduct of a scan).
 */
export function ReplenishmentSection() {
  const { hasPermission } = usePermissions();
  const canWrite = hasPermission('task-write');

  const { data, isLoading } = useReplenishmentNeeds();
  const scan = useScanReplenishment();
  const needs = data ?? [];

  return (
    <div className="space-y-4">
      <SectionCard
        title="Replenishment needs"
        action={
          canWrite && (
            <Button
              onClick={() => scan.mutate()}
              disabled={scan.isPending}
              data-testid="scan-replenishment-button"
            >
              <RefreshCw className={`mr-2 size-4 ${scan.isPending ? 'animate-spin' : ''}`} />
              {scan.isPending ? 'Scanning...' : 'Scan now'}
            </Button>
          )
        }
      >
        {isLoading ? (
          <div className="space-y-2">
            <Skeleton className="h-10 w-full" />
            <Skeleton className="h-10 w-full" />
          </div>
        ) : needs.length === 0 ? (
          <p className="py-6 text-center text-sm text-muted-foreground">
            No open replenishment needs.
          </p>
        ) : (
          <Table data-testid="replenishment-needs-table">
            <TableHeader>
              <TableRow>
                <TableHead>Location</TableHead>
                <TableHead>SKU</TableHead>
                <TableHead className="text-right">Current</TableHead>
                <TableHead className="text-right">Min</TableHead>
                <TableHead className="text-right">Target</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Task</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {needs.map((n) => (
                <TableRow
                  key={n.fixAssignmentId}
                  data-testid={`replenishment-need-${n.fixAssignmentId}`}
                >
                  <TableCell className="font-mono text-[13px]">{n.locationName}</TableCell>
                  <TableCell className="font-mono text-[13px]">
                    {n.itemDataNumber ?? EMPTY}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{n.currentAmount}</TableCell>
                  <TableCell className="text-right tabular-nums">
                    {n.minAmount ?? EMPTY}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">
                    {n.desiredAmount ?? EMPTY}
                  </TableCell>
                  <TableCell>
                    {n.belowMin ? (
                      <Badge variant="destructive">Below min</Badge>
                    ) : (
                      <Badge variant="outline">Low</Badge>
                    )}
                  </TableCell>
                  <TableCell>
                    {n.hasOpenTask && <Badge variant="secondary">Open task</Badge>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </SectionCard>
      {scan.data && (scan.data.generated.length > 0 || scan.data.shortfalls.length > 0) && (
        <SectionCard title="Last scan results">
          <p className="text-sm text-muted-foreground">
            This browser session only — results are not stored.
          </p>
          <ScanResultsSection generated={scan.data.generated} shortfalls={scan.data.shortfalls} />
        </SectionCard>
      )}
    </div>
  );
}
