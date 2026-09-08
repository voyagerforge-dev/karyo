import { Link } from 'react-router';
import { ArrowUpRight } from 'lucide-react';
import { SectionCard } from '@/components/control/section-card';
import { AttributeGrid } from '@/components/control/attribute-grid';
import { Skeleton } from '@/components/ui/skeleton';
import { useGoodsReceipt } from '@/pages/receiving/use-receiving';
import { refSourceId, type WorkItemResponse } from '@/types/work';

export interface ReceivePaneProps {
  workItem: WorkItemResponse;
}

/**
 * RECEIVE work-item detail (Task 6, inbound-completion row 4 residual): a
 * summary + deep link, the same shape as [CountPane] — the actual receiving
 * (scan lines, over-receipt confirms, finish) happens on the dedicated
 * Receiving workbench, which owns that state machine.
 */
export function ReceivePane({ workItem }: ReceivePaneProps) {
  const receiptId = refSourceId(workItem.ref);
  const { data: receipt, isLoading } = useGoodsReceipt(receiptId);
  const claimState =
    workItem.state === 'CLAIMED' ? `Claimed · ${workItem.claimedBy}` : 'Open';

  if (isLoading || !receipt) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-6 w-1/2" />
        <Skeleton className="h-32 w-full" />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <SectionCard title="Receive">
        <AttributeGrid
          items={[
            { label: 'Receipt', value: receipt.receiptNumber },
            { label: 'State', value: receipt.stateName },
            { label: 'Dock', value: receipt.dockLocationName },
            { label: 'Linked ASNs', value: String(receipt.asns.length) },
            { label: 'Priority', value: String(workItem.priority) },
            { label: 'Claim state', value: claimState },
          ]}
        />
        <div className="mt-5 flex items-center justify-between gap-3 rounded-md border border-border bg-muted/30 p-3">
          <p className="text-[13px] text-muted-foreground">
            Receiving happens on the Receiving workbench.
          </p>
          <Link
            to={`/receiving/${receiptId}`}
            className="inline-flex shrink-0 items-center gap-1.5 rounded-md bg-primary px-3 py-1.5 text-[13px] font-semibold text-primary-foreground"
            data-testid="receive-open-workbench"
          >
            Open workbench
            <ArrowUpRight className="size-4" />
          </Link>
        </div>
      </SectionCard>
    </div>
  );
}
