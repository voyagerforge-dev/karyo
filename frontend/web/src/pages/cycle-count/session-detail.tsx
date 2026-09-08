import { useEffect, useRef, useState } from 'react';
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
import { SectionCard } from '@/components/control/section-card';
import { useSession, useCancelOrder } from './use-cycle-count';
import { CountEntryForm } from './count-entry-form';
import { ReviewPanel } from './review-panel';
import type { CountOrderView } from '@/types/cycle-count';

function orderStateName(state: number): string {
  if (state === 50) return 'To count';
  if (state === 500) return 'In review';
  if (state === 700) return 'Done';
  if (state === 800) return 'Cancelled';
  return `State ${state}`;
}

function orderStateVariant(
  state: number,
): 'default' | 'secondary' | 'outline' | 'destructive' {
  if (state === 50) return 'outline';
  if (state === 500) return 'default';
  if (state === 700) return 'secondary';
  if (state === 800) return 'destructive';
  return 'outline';
}

interface SessionDetailProps {
  sessionId: number;
  /** Order id to auto-open (count or review view, by its state) once the
   * session loads -- the `?order=` deep link from a COUNT work item. Applied
   * at most once per distinct id so it doesn't keep re-forcing the entry view
   * on every background refetch. */
  initialOrderId?: number | null;
}

/**
 * Session workspace pane (P4 Task 3) -- ports the old SessionDrawer's
 * content 1:1 onto the Control master-detail detail slot. Order cards,
 * activeOrderId/activeMode logic, CountEntryForm/ReviewPanel embedding and
 * the deep-link effect are unchanged; only the outer Sheet chrome is gone
 * (the pane is a permanent slot, not a slide-over).
 */
export function SessionDetail({ sessionId, initialOrderId }: SessionDetailProps) {
  const { data: session, isLoading } = useSession(sessionId);

  // Which order is open for counting or review
  const [activeOrderId, setActiveOrderId] = useState<number | undefined>(undefined);
  const [activeMode, setActiveMode] = useState<'count' | 'review' | undefined>(undefined);

  // St7: cancel affordance for a GENERATED (state 50) order row -- COUNTED (state 500)
  // orders are cancelled from ReviewPanel instead, alongside Accept/Recount.
  const cancelOrder = useCancelOrder();
  const [orderToCancel, setOrderToCancel] = useState<CountOrderView | undefined>(undefined);

  const appliedDeepLinkRef = useRef<number | undefined>(undefined);
  useEffect(() => {
    if (initialOrderId == null || !session) return;
    if (appliedDeepLinkRef.current === initialOrderId) return;
    const order = session.orders.find((o) => o.id === initialOrderId);
    if (!order) return;
    appliedDeepLinkRef.current = initialOrderId;
    if (order.state === 50) {
      setActiveOrderId(order.id);
      setActiveMode('count');
    } else if (order.state === 500) {
      setActiveOrderId(order.id);
      setActiveMode('review');
    }
  }, [initialOrderId, session]);

  const handleOrderClick = (order: CountOrderView) => {
    if (order.state === 50) {
      setActiveOrderId(order.id);
      setActiveMode('count');
    } else if (order.state === 500) {
      setActiveOrderId(order.id);
      setActiveMode('review');
    }
  };

  const handleActionDone = () => {
    setActiveOrderId(undefined);
    setActiveMode(undefined);
  };

  return (
    <div className="space-y-4" data-testid="session-detail">
      <SectionCard>
        <h1 className="font-display numeric text-[20px] font-bold text-foreground">
          {session?.sessionNumber ?? (isLoading ? 'Loading…' : '—')}
        </h1>
        <p className="mt-1 text-[13px] text-foreground/70">
          {/* blindCount is not exposed by CountSessionView (it's per-order on the entity,
              not surfaced by any view today) -- an honest "Count session" label rather
              than a fabricated Blind/Standard distinction. */}
          Count session — {session?.orders.length ?? 0} location
          {(session?.orders.length ?? 0) !== 1 ? 's' : ''}
        </p>
      </SectionCard>

      {activeOrderId != null && activeMode === 'count' ? (
        <div>
          <Button
            variant="ghost"
            size="sm"
            className="mb-2 -ml-1"
            onClick={handleActionDone}
          >
            ← Back to session
          </Button>
          <CountEntryForm orderId={activeOrderId} onDone={handleActionDone} />
        </div>
      ) : activeOrderId != null && activeMode === 'review' ? (
        <div>
          <Button
            variant="ghost"
            size="sm"
            className="mb-2 -ml-1"
            onClick={handleActionDone}
          >
            ← Back to session
          </Button>
          <ReviewPanel orderId={activeOrderId} onDone={handleActionDone} />
        </div>
      ) : (
        <div className="space-y-2">
          {isLoading && (
            <p className="py-4 text-center text-sm text-muted-foreground">Loading…</p>
          )}
          {session?.orders.map((order) => (
            <div
              key={order.id}
              className={`rounded-md border p-3 transition-colors ${
                order.state === 50 || order.state === 500
                  ? 'cursor-pointer hover:bg-accent'
                  : ''
              }`}
              onClick={() => handleOrderClick(order)}
              data-testid={`order-row-${order.id}`}
            >
              <div className="flex items-center justify-between">
                <span className="font-mono text-[13px]">{order.orderNumber}</span>
                <Badge variant={orderStateVariant(order.state)}>
                  {orderStateName(order.state)}
                </Badge>
              </div>
              <p className="mt-0.5 text-xs text-muted-foreground">{order.locationName}</p>
              {order.state === 50 && (
                <div className="mt-1 flex items-center justify-between">
                  <p className="text-xs text-primary">Click to open count entry →</p>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="h-6 px-2 text-xs text-destructive hover:text-destructive"
                    onClick={(e) => {
                      e.stopPropagation();
                      setOrderToCancel(order);
                    }}
                    disabled={cancelOrder.isPending}
                    data-testid={`order-cancel-${order.id}`}
                  >
                    Cancel
                  </Button>
                </div>
              )}
              {order.state === 500 && (
                <p className="mt-1 text-xs text-primary">Click to review discrepancies →</p>
              )}
            </div>
          ))}
          {session && session.orders.length === 0 && (
            <p className="py-4 text-center text-sm text-muted-foreground">
              No count orders in this session.
            </p>
          )}
        </div>
      )}

      <AlertDialog
        open={orderToCancel != null}
        onOpenChange={(open) => !open && setOrderToCancel(undefined)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Cancel this count order?</AlertDialogTitle>
            <AlertDialogDescription>
              This drops &quot;{orderToCancel?.locationName}&quot; from the session -- no
              replacement order is generated. This cannot be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Keep order</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (orderToCancel == null) return;
                cancelOrder.mutate(orderToCancel.id, {
                  onSuccess: () => setOrderToCancel(undefined),
                });
              }}
            >
              Cancel order
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
