import { Check } from 'lucide-react';
import { cn } from '@/lib/utils';
import { PIPELINE_STAGES } from './order-status';

interface OrderPipelineProps {
  /** Current stage index 0..4 (from getOrderStatus). */
  stageIndex: number;
  /** Per-node timestamp text ("—" when unknown). Length 5, aligned to PIPELINE_STAGES. */
  timestamps: readonly string[];
}

/**
 * Fulfillment pipeline (README #11): Created→Allocated→Picking→Packed→Shipped.
 * done = lime check, current = lime dot, pending = grey; connectors lime up to
 * progress. Timestamps under each node: Placed = order.created and Shipped =
 * order.shippedAt are real (B8b); the three intermediate stages stay an honest
 * "—" — the backend has no per-stage history to show there.
 */
export function OrderPipeline({ stageIndex, timestamps }: OrderPipelineProps) {
  return (
    <section className="mb-4 rounded-2xl border border-border bg-card p-[20px_22px]">
      <div className="flex items-start">
        {PIPELINE_STAGES.map((stage, i) => {
          const done = i < stageIndex;
          const current = i === stageIndex;
          const reached = i <= stageIndex;
          return (
            <div
              key={stage}
              className="relative flex flex-1 flex-col items-center"
            >
              {/* connector into this node (colored to progress) */}
              {i > 0 && (
                <span
                  aria-hidden
                  className={cn(
                    'absolute left-[-50%] right-1/2 top-[13px] h-[2px]',
                    reached ? 'bg-primary' : 'bg-border',
                  )}
                />
              )}
              <div
                className={cn(
                  'relative z-[2] flex size-7 items-center justify-center rounded-full border-2',
                  done && 'border-primary bg-[var(--acc-deep)] text-primary',
                  current && 'border-primary bg-[var(--acc-deep)]',
                  !done && !current && 'border-border bg-card',
                )}
              >
                {done && <Check className="size-3.5" strokeWidth={3} />}
                {current && (
                  <span className="size-2 rounded-full bg-primary" aria-hidden />
                )}
              </div>
              <div
                className={cn(
                  'mt-[9px] text-[12px] font-semibold',
                  reached ? 'text-foreground' : 'text-muted-foreground/70',
                )}
              >
                {stage}
              </div>
              <div className="numeric mt-0.5 text-[10.5px] text-muted-foreground/70">
                {timestamps[i] ?? 'pending'}
              </div>
            </div>
          );
        })}
      </div>
    </section>
  );
}
