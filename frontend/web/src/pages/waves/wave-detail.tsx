import { useState } from 'react';
import { Ban, Play } from 'lucide-react';
import { Link } from 'react-router';
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
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Button } from '@/components/ui/button';
import { Progress } from '@/components/ui/progress';
import { Skeleton } from '@/components/ui/skeleton';
import { SectionCard } from '@/components/control/section-card';
import { StatusPill } from '@/components/control/status-pill';
import { useWave, useWaveProgress, useReleaseWave, useCancelWave, useMarkGroupReady } from '@/features/waves/use-waves';
import {
  WAVE_STATE_TONE,
  WAVE_STATE_LABEL,
  CONSOLIDATION_STATE_TONE,
  CONSOLIDATION_STATE_LABEL,
  canRelease,
  canCancel,
  canMarkGroupReady,
} from './wave-status';

interface WaveDetailProps {
  waveId: number;
  canWrite: boolean;
}

function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString();
}

/** Selection-rules sprint provenance display (IMPORTANT-3): the resolved selection strategy key,
 *  plus the bound rule's name when the wave was rule-based selected. */
function fmtSelection(strategy: string | null | undefined, ruleName: string | null | undefined): string {
  if (!strategy) return '—';
  return ruleName ? `${strategy} / ${ruleName}` : strategy;
}

/**
 * Wave detail pane: lifecycle banner + Release/Cancel actions, orders table (shortage badge per
 * order that has one), consolidation groups (READY action), and a live progress bar polled from
 * `/progress` while the wave is actively working (see `useWaveProgress`).
 */
export function WaveDetail({ waveId, canWrite }: WaveDetailProps) {
  const { data, isLoading } = useWave(waveId);
  const releaseMutation = useReleaseWave();
  const cancelMutation = useCancelWave();
  const readyMutation = useMarkGroupReady();
  const [confirmRelease, setConfirmRelease] = useState(false);
  const [confirmCancel, setConfirmCancel] = useState(false);

  const wave = data?.wave;
  const progress = useWaveProgress(waveId, wave?.state);

  if (isLoading || !data || !wave) {
    return (
      <SectionCard>
        <div className="space-y-3">
          <Skeleton className="h-6 w-1/2" />
          <Skeleton className="h-32 w-full" />
        </div>
      </SectionCard>
    );
  }

  const tone = WAVE_STATE_TONE[wave.state];
  const shortagesByOrder = new Map<number, number>();
  for (const s of data.shortages) {
    shortagesByOrder.set(s.orderId, (shortagesByOrder.get(s.orderId) ?? 0) + 1);
  }

  const isMutating = releaseMutation.isPending || cancelMutation.isPending;

  return (
    <div className="space-y-4" data-testid="wave-detail">
      <SectionCard>
        <div className="flex items-center gap-3">
          <h1 className="font-display numeric text-[20px] font-bold text-foreground">
            {wave.waveNumber}
          </h1>
          <StatusPill label={WAVE_STATE_LABEL[wave.state]} tone={tone} />
        </div>
        <p className="mt-1 text-[13px] text-foreground/70">
          {wave.totalOrders} orders · {wave.totalLines} lines
        </p>

        <dl className="mt-4 grid grid-cols-2 gap-3 text-[12.5px] sm:grid-cols-4">
          <div>
            <dt className="text-muted-foreground">Planned release</dt>
            <dd className="numeric mt-0.5 text-foreground">{fmtDateTime(wave.plannedReleaseAt)}</dd>
          </div>
          <div>
            <dt className="text-muted-foreground">Released</dt>
            <dd className="numeric mt-0.5 text-foreground">{fmtDateTime(wave.releasedAt)}</dd>
          </div>
          <div>
            <dt className="text-muted-foreground">Completed</dt>
            <dd className="numeric mt-0.5 text-foreground">{fmtDateTime(wave.completedAt)}</dd>
          </div>
          <div>
            <dt className="text-muted-foreground">Selection</dt>
            <dd className="mt-0.5 text-foreground" data-testid="wave-selection-meta">
              {fmtSelection(wave.selectionStrategy, wave.selectionRuleName)}
            </dd>
          </div>
        </dl>

        {canWrite && (
          <div className="mt-5 flex flex-wrap items-center gap-2 border-t border-border pt-4">
            {canRelease(wave.state) && (
              <Button
                onClick={() => setConfirmRelease(true)}
                disabled={isMutating}
                data-testid="wave-release-button"
              >
                <Play className="size-4" />
                Release
              </Button>
            )}
            {canCancel(wave.state) && (
              <Button
                variant="outline"
                className="text-destructive"
                onClick={() => setConfirmCancel(true)}
                disabled={isMutating}
                data-testid="wave-cancel-button"
              >
                <Ban className="size-4" />
                Cancel
              </Button>
            )}
          </div>
        )}
      </SectionCard>

      {progress.data && (
        <SectionCard title="Progress">
          <div className="space-y-3" data-testid="wave-progress">
            <Progress
              value={
                progress.data.totalLines > 0
                  ? Math.round((progress.data.pickedLines / progress.data.totalLines) * 100)
                  : 0
              }
            />
            <div className="flex flex-wrap gap-x-6 gap-y-1 text-[12.5px] text-muted-foreground">
              <span className="numeric">
                {progress.data.pickedLines}/{progress.data.totalLines} lines picked
              </span>
              <span className="numeric">{progress.data.openPickOrders} open pick orders</span>
              <span className="numeric">
                {progress.data.groupsReady}/{progress.data.groupsTotal} groups ready
              </span>
            </div>
          </div>
        </SectionCard>
      )}

      <SectionCard title={`Orders (${data.orders.length})`} noPad>
        <Table data-testid="wave-orders-table">
          <TableHeader>
            <TableRow>
              <TableHead>Order #</TableHead>
              <TableHead>Customer</TableHead>
              <TableHead>State</TableHead>
              <TableHead className="text-right">Priority</TableHead>
              <TableHead>Shortages</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.orders.map((o) => {
              const shortageCount = shortagesByOrder.get(o.orderId) ?? 0;
              return (
                <TableRow key={o.orderId} data-testid={`wave-order-row-${o.orderId}`}>
                  <TableCell className="font-mono text-[13px]">{o.orderNumber}</TableCell>
                  <TableCell>{o.customerName ?? '—'}</TableCell>
                  <TableCell>{o.state}</TableCell>
                  <TableCell className="numeric text-right">{o.prio}</TableCell>
                  <TableCell>
                    {shortageCount > 0 ? (
                      <StatusPill label={`${shortageCount} short`} tone="red" />
                    ) : (
                      '—'
                    )}
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </SectionCard>

      <SectionCard title={`Consolidation groups (${data.groups.length})`} noPad>
        <Table data-testid="wave-groups-table">
          <TableHeader>
            <TableRow>
              <TableHead>Slot</TableHead>
              <TableHead>Destination</TableHead>
              <TableHead>State</TableHead>
              <TableHead className="text-right">Pick orders</TableHead>
              <TableHead className="text-right">Sorted</TableHead>
              <TableHead className="text-right">Packed</TableHead>
              <TableHead>Shipment</TableHead>
              {canWrite && <TableHead className="text-right">Action</TableHead>}
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.groups.map((g) => (
              <TableRow key={g.id} data-testid={`wave-group-row-${g.id}`}>
                <TableCell className="font-mono" data-testid={`wave-group-slot-${g.id}`}>
                  {g.sortSlot}
                </TableCell>
                <TableCell className="font-mono text-[13px]">{g.destinationKey}</TableCell>
                <TableCell>
                  <StatusPill
                    label={CONSOLIDATION_STATE_LABEL[g.state]}
                    tone={CONSOLIDATION_STATE_TONE[g.state]}
                  />
                </TableCell>
                <TableCell className="numeric text-right">
                  {g.completedPickOrders}/{g.totalPickOrders}
                </TableCell>
                <TableCell className="numeric text-right" data-testid={`wave-group-sorted-${g.id}`}>
                  {g.sortedAmount}/{g.pickedAmount}
                </TableCell>
                <TableCell className="numeric text-right" data-testid={`wave-group-packed-${g.id}`}>
                  {g.packedAmount}/{g.sortedAmount}
                </TableCell>
                <TableCell>
                  {g.shipmentId != null ? (
                    <Link
                      // /shipments is a master-detail list, not a per-id route: the group's
                      // shipment is named as a query param the list reads on load and selects.
                      to={`/shipments?shipment=${g.shipmentId}`}
                      className="text-signal underline-offset-2 hover:underline"
                      data-testid={`wave-group-shipment-${g.id}`}
                    >
                      shipment
                    </Link>
                  ) : (
                    '—'
                  )}
                </TableCell>
                {canWrite && (
                  <TableCell className="text-right">
                    {canMarkGroupReady(g.state) && (
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={readyMutation.isPending || g.sortedAmount < g.pickedAmount}
                        title={
                          g.sortedAmount < g.pickedAmount
                            ? `${g.pickedAmount - g.sortedAmount} units unsorted at the put wall`
                            : undefined
                        }
                        onClick={() => readyMutation.mutate({ waveId, groupId: g.id })}
                        data-testid={`wave-group-ready-btn-${g.id}`}
                      >
                        Ready
                      </Button>
                    )}
                  </TableCell>
                )}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </SectionCard>

      {data.unfilledOrders.length > 0 && (
        <SectionCard title="Unfilled members" noPad>
          <Table data-testid="wave-unfilled-table">
            <TableHeader>
              <TableRow>
                <TableHead>Order #</TableHead>
                <TableHead>Customer</TableHead>
                <TableHead>State</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.unfilledOrders.map((o) => (
                <TableRow key={o.orderId} data-testid={`wave-unfilled-row-${o.orderId}`}>
                  <TableCell className="font-mono text-[13px]">{o.orderNumber}</TableCell>
                  <TableCell>{o.customerName ?? '—'}</TableCell>
                  <TableCell>{o.state}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </SectionCard>
      )}

      <AlertDialog open={confirmRelease} onOpenChange={setConfirmRelease}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Release wave?</AlertDialogTitle>
            <AlertDialogDescription>
              {`This allocates stock and generates batch pick orders for "${wave.waveNumber}". This cannot be undone.`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Keep planned</AlertDialogCancel>
            <AlertDialogAction
              onClick={() =>
                releaseMutation.mutate(waveId, { onSuccess: () => setConfirmRelease(false) })
              }
              disabled={releaseMutation.isPending}
              data-testid="wave-release-confirm-btn"
            >
              Release wave
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <AlertDialog open={confirmCancel} onOpenChange={setConfirmCancel}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Cancel wave?</AlertDialogTitle>
            <AlertDialogDescription>
              {`This cancels "${wave.waveNumber}". This cannot be undone.`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Keep wave</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              onClick={() =>
                cancelMutation.mutate(waveId, { onSuccess: () => setConfirmCancel(false) })
              }
              disabled={cancelMutation.isPending}
              data-testid="wave-cancel-confirm-btn"
            >
              Cancel wave
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
