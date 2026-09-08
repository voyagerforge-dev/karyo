import { Link } from 'react-router';
import { ArrowUpRight } from 'lucide-react';
import { SectionCard } from '@/components/control/section-card';
import { AttributeGrid } from '@/components/control/attribute-grid';
import { refSourceId, type WorkItemResponse } from '@/types/work';

export interface CountPaneProps {
  workItem: WorkItemResponse;
}

/**
 * COUNT work-item detail (P3 Task 4, spec D2 exception): counting itself
 * happens on the dedicated Cycle Count screen (blind entry + discrepancy
 * review own their own state machine there), so this pane is a summary +
 * deep link rather than an inline count form.
 */
export function CountPane({ workItem }: CountPaneProps) {
  const countOrderId = refSourceId(workItem.ref);
  const claimState =
    workItem.state === 'CLAIMED' ? `Claimed · ${workItem.claimedBy}` : 'Open';

  return (
    <div className="space-y-4">
      <SectionCard title="Count">
        <AttributeGrid
          items={[
            { label: 'Location', value: workItem.primaryLocation },
            { label: 'Priority', value: String(workItem.priority) },
            { label: 'Summary', value: workItem.summary },
            { label: 'Claim state', value: claimState },
          ]}
        />
        <div className="mt-5 flex items-center justify-between gap-3 rounded-md border border-border bg-muted/30 p-3">
          <p className="text-[13px] text-muted-foreground">
            Counting happens on the Cycle Count screen.
          </p>
          <Link
            to={`/cycle-count?order=${countOrderId}`}
            className="inline-flex shrink-0 items-center gap-1.5 rounded-md bg-primary px-3 py-1.5 text-[13px] font-semibold text-primary-foreground"
            data-testid="count-open-cycle-count"
          >
            Open in Cycle Count
            <ArrowUpRight className="size-4" />
          </Link>
        </div>
      </SectionCard>
    </div>
  );
}
