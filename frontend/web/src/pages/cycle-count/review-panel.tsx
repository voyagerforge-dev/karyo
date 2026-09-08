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
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { useCountOrder, useAccept, useRecount, useCancelOrder } from './use-cycle-count';
import type { CountLineView } from '@/types/cycle-count';

interface ReviewPanelProps {
  orderId: number;
  onDone: () => void;
}

function discrepancyBadge(planned: number, counted: number | null) {
  if (counted === null) return null;
  const diff = counted - planned;
  if (diff === 0) return <Badge variant="secondary">Match</Badge>;
  if (diff > 0)
    return <Badge variant="default">+{diff}</Badge>;
  return <Badge variant="destructive">{diff}</Badge>;
}

/**
 * Discrepancy review panel.
 *
 * Fetches the full (review) view of a COUNTED order — shows planned vs counted
 * amounts per line with variance highlighting.
 * Manager / supervisor can Accept (apply adjustments, FINISHED) or Recount
 * (reset to GENERATED for a fresh blind count).
 */
export function ReviewPanel({ orderId, onDone }: ReviewPanelProps) {
  const { data: order, isLoading } = useCountOrder(orderId);
  const accept = useAccept(orderId);
  const recount = useRecount(orderId);
  const cancelOrder = useCancelOrder();
  const [confirmCancel, setConfirmCancel] = useState(false);

  if (isLoading) {
    return <p className="py-6 text-center text-sm text-muted-foreground">Loading review…</p>;
  }

  if (!order) {
    return <p className="py-6 text-center text-sm text-muted-foreground">Order not found.</p>;
  }

  if (order.state === 700) {
    return (
      <p className="py-6 text-center text-sm text-muted-foreground">
        This count order is finished.
      </p>
    );
  }

  if (order.state !== 500) {
    return (
      <p className="py-6 text-center text-sm text-muted-foreground">
        Order is not yet counted — submit the blind count first.
      </p>
    );
  }

  const isPending = accept.isPending || recount.isPending || cancelOrder.isPending;

  return (
    <div className="space-y-4" data-testid="review-panel">
      <div>
        <p className="text-sm font-medium">{order.locationName}</p>
        <p className="text-xs text-muted-foreground">
          Review counted quantities against expected stock. Accept to apply adjustments, or request a
          recount.
        </p>
      </div>

      <Table data-testid="review-table">
        <TableHeader>
          <TableRow>
            <TableHead>Item</TableHead>
            <TableHead>Lot</TableHead>
            <TableHead className="text-right">Planned</TableHead>
            <TableHead className="text-right">Counted</TableHead>
            <TableHead className="text-center">Variance</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {order.lines.map((line: CountLineView) => (
            <TableRow key={line.id} data-testid={`review-line-${line.id}`}>
              <TableCell className="font-mono text-[13px]">{line.itemDataNumber}</TableCell>
              <TableCell className="text-[13px]">{line.lotNumber ?? '—'}</TableCell>
              <TableCell className="text-right tabular-nums">{line.plannedAmount}</TableCell>
              <TableCell className="text-right tabular-nums">
                {line.countedAmount ?? '—'}
              </TableCell>
              <TableCell className="text-center">
                {discrepancyBadge(line.plannedAmount, line.countedAmount)}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>

      <div className="flex justify-end gap-2">
        <Button variant="outline" onClick={onDone} disabled={isPending}>
          Back
        </Button>
        <Button
          variant="outline"
          className="text-destructive"
          onClick={() => setConfirmCancel(true)}
          disabled={isPending}
          data-testid="cancel-order-button"
        >
          Cancel
        </Button>
        <Button
          variant="outline"
          onClick={() => recount.mutate(undefined, { onSuccess: onDone })}
          disabled={isPending}
          data-testid="recount-button"
        >
          Request recount
        </Button>
        <Button
          onClick={() => accept.mutate(undefined, { onSuccess: onDone })}
          disabled={isPending}
          data-testid="accept-button"
        >
          Accept
        </Button>
      </div>

      <AlertDialog open={confirmCancel} onOpenChange={setConfirmCancel}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Cancel this count order?</AlertDialogTitle>
            <AlertDialogDescription>
              This drops &quot;{order.locationName}&quot; from the session -- no replacement order is
              generated. This cannot be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Keep order</AlertDialogCancel>
            <AlertDialogAction
              onClick={() =>
                cancelOrder.mutate(orderId, {
                  onSuccess: () => {
                    setConfirmCancel(false);
                    onDone();
                  },
                })
              }
            >
              Cancel order
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
