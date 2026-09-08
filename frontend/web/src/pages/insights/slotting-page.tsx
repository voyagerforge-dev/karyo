import { Lock } from 'lucide-react';
import { useLicense } from '@/features/license/use-license';
import { useSlotting } from '@/pages/insights/use-slotting';
import { Badge } from '@/components/ui/badge';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { cn } from '@/lib/utils';

/**
 * Insights → Slotting (v1.7c): per-SKU re-slot recommendations computed by
 * the `karyo-slotting` module (`GET /api/v1/slotting/recommendations`).
 *
 * Gated behind the `slotting` paid-license entitlement
 * (`useLicense().isEntitled('slotting')`, backed by `GET /api/v1/license`
 * — the gate-discovery endpoint that is never itself license-gated). The
 * gate is checked BEFORE `useSlotting` is invoked, so an unentitled tenant
 * never calls `/api/v1/slotting/recommendations` (which would 403 anyway).
 */
function LockedSlottingPanel() {
  return (
    <div
      data-testid="slotting-locked"
      className="flex flex-col items-center justify-center gap-3 rounded-2xl border border-border bg-card px-8 py-20 text-center"
    >
      <div className="flex size-12 items-center justify-center rounded-full bg-[rgba(199,242,78,0.1)]">
        <Lock className="size-5 text-primary" strokeWidth={2} />
      </div>
      <h1 className="font-display m-0 text-[20px] font-bold tracking-[-0.02em] text-foreground">
        Slotting is a paid add-on
      </h1>
      <p className="m-0 max-w-md text-[13px] text-muted-foreground">
        Per-SKU re-slot recommendations, computed from ABC velocity classes and current
        location order-index — contact your account team to enable Slotting for this tenant.
      </p>
    </div>
  );
}

function SlottingTable() {
  const { data, isLoading, isError } = useSlotting();

  if (isLoading) {
    return <p className="text-[13px] text-muted-foreground">Loading…</p>;
  }
  if (isError) {
    return (
      <div className="rounded-2xl border border-border bg-card p-4 text-[13px] text-destructive">
        Couldn&apos;t load slotting recommendations.
      </div>
    );
  }
  if (!data || data.length === 0) {
    return (
      <div className="rounded-2xl border border-border bg-card p-8 text-center text-[13px] text-muted-foreground">
        No re-slot recommendations yet — velocity history is still building.
      </div>
    );
  }

  return (
    <div className="rounded-2xl border border-border bg-card">
      <Table data-testid="slotting-table">
        <TableHeader>
          <TableRow>
            <TableHead>SKU</TableHead>
            <TableHead>ABC class</TableHead>
            <TableHead className="text-right">Velocity rank</TableHead>
            <TableHead>Current location</TableHead>
            <TableHead className="text-right">Order index</TableHead>
            <TableHead>Direction</TableHead>
            <TableHead>Reason</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {data.map((row) => (
            <TableRow
              key={row.sku}
              data-testid={`slotting-row-${row.sku}`}
              className={cn(row.direction === 'PROMOTE' && 'bg-primary/5')}
            >
              <TableCell className="font-medium">{row.sku}</TableCell>
              <TableCell>{row.abcClass}</TableCell>
              <TableCell className="numeric text-right">{row.velocityRank}</TableCell>
              <TableCell>{row.currentLocation}</TableCell>
              <TableCell className="numeric text-right">{row.currentOrderIndex}</TableCell>
              <TableCell>
                <Badge variant={row.direction === 'PROMOTE' ? 'success' : 'secondary'}>
                  {row.direction}
                </Badge>
              </TableCell>
              <TableCell className="text-muted-foreground">{row.reason}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

export function SlottingPage() {
  const license = useLicense();

  let body: React.ReactNode;
  if (license.isLoading) {
    body = <p className="text-[13px] text-muted-foreground">Loading…</p>;
  } else if (!license.isEntitled('slotting')) {
    body = <LockedSlottingPanel />;
  } else {
    body = <SlottingTable />;
  }

  return (
    <div data-testid="slotting-page">
      <div className="mb-5">
        <h1 className="font-display text-2xl font-bold tracking-[-0.02em] text-foreground">
          Slotting
        </h1>
        <p className="mt-1 text-[13px] text-muted-foreground">
          Per-SKU re-slot recommendations based on ABC velocity and current placement.
        </p>
      </div>
      {body}
    </div>
  );
}
