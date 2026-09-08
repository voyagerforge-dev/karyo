import type { ReactNode } from 'react';
import { Pencil } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { SectionCard } from '@/components/control/section-card';
import { AttributeGrid, type AttributeGridItem } from '@/components/control/attribute-grid';
import { RELEASE_MODES, type OrderStrategyResponse, type StorageStrategyResponse } from '@/types/strategies';

type Props =
  | { kind: 'order'; strategy: OrderStrategyResponse; canWrite: boolean; onEdit: () => void }
  | { kind: 'storage'; strategy: StorageStrategyResponse; canWrite: boolean; onEdit: () => void };

function yesNo(value: boolean): string {
  return value ? 'Yes' : 'No';
}

// shortPickMode is a real closed backend enum -- keep this label map in sync
// with order-strategy-form.tsx's SHORT_PICK_MODES.
const SHORT_PICK_LABELS: Record<string, string> = {
  FOLLOW_UP: 'Follow up',
  FOLLOW_UP_THEN_SUBSTITUTE: 'Follow up, then substitute',
  SUBSTITUTE_ONLY: 'Substitute only',
  NONE: 'None',
};

/** Shared shell: title, kind subtitle, write-gated Edit button, AttributeGrid + optional extra block. */
function DetailShell({
  name,
  subtitle,
  items,
  canWrite,
  onEdit,
  extra,
}: {
  name: string;
  subtitle: string;
  items: AttributeGridItem[];
  canWrite: boolean;
  onEdit: () => void;
  extra?: ReactNode;
}) {
  return (
    <div className="space-y-4">
      <SectionCard>
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0">
            <h1 className="font-display numeric text-[20px] font-bold text-foreground">{name}</h1>
            <p className="mt-1 text-[13px] text-foreground/70">{subtitle}</p>
          </div>
          {canWrite && (
            <Button onClick={onEdit} data-testid="strategy-edit">
              <Pencil className="size-4" />
              Edit
            </Button>
          )}
        </div>

        <div className="mt-5">
          <AttributeGrid items={items} />
        </div>

        {extra}
      </SectionCard>
    </div>
  );
}

function OrderStrategyDetailBody({
  strategy,
  canWrite,
  onEdit,
}: {
  strategy: OrderStrategyResponse;
  canWrite: boolean;
  onEdit: () => void;
}) {
  const hasExtensionProperties = Object.keys(strategy.extensionProperties).length > 0;
  // releaseMode rides in extensionProperties (untyped on the backend DTO), default MANUAL --
  // Task 8: show it as its own line, near the ext JSON, not folded into the AttributeGrid's
  // fixed 9 backend-owned columns.
  const releaseModeValue = strategy.extensionProperties.releaseMode;
  const releaseMode =
    typeof releaseModeValue === 'string' &&
    (RELEASE_MODES as readonly string[]).includes(releaseModeValue)
      ? releaseModeValue
      : 'MANUAL';

  return (
    <DetailShell
      name={strategy.name}
      subtitle="Order strategy"
      canWrite={canWrite}
      onEdit={onEdit}
      items={[
        { label: 'Use locked stock', value: yesNo(strategy.useLockedStock) },
        { label: 'Prefer complete', value: yesNo(strategy.preferComplete) },
        { label: 'Prefer matching', value: yesNo(strategy.preferMatching) },
        {
          label: 'Short pick mode',
          value: SHORT_PICK_LABELS[strategy.shortPickMode] ?? strategy.shortPickMode,
        },
        { label: 'Enforce lot', value: yesNo(strategy.enforceLot) },
        { label: 'Complete handling', value: String(strategy.completeHandling) },
        { label: 'Shortfall strategy', value: strategy.shortfallStrategy },
        { label: 'Pick difference strategy', value: strategy.pickDifferenceStrategy },
        { label: 'Packout strategy', value: strategy.packoutStrategy },
      ]}
      extra={
        <>
          <div className="mt-5 border-t border-border pt-4">
            <div className="flex items-center justify-between">
              <span className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground/70">
                Release mode
              </span>
              <span
                className="text-[12.5px] font-semibold text-foreground/90"
                data-testid="strategy-release-mode"
              >
                {releaseMode}
              </span>
            </div>
          </div>
          {hasExtensionProperties && (
            <div className="mt-3">
              <div className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground/70">
                Extension properties
              </div>
              <pre className="mt-2 overflow-x-auto rounded-lg bg-muted p-3 font-mono text-[12px] text-foreground/90">
                {JSON.stringify(strategy.extensionProperties, null, 2)}
              </pre>
            </div>
          )}
        </>
      }
    />
  );
}

function StorageStrategyDetailBody({
  strategy,
  canWrite,
  onEdit,
}: {
  strategy: StorageStrategyResponse;
  canWrite: boolean;
  onEdit: () => void;
}) {
  return (
    <DetailShell
      name={strategy.name}
      subtitle="Storage strategy"
      canWrite={canWrite}
      onEdit={onEdit}
      items={[
        { label: 'Zone', value: strategy.zoneId != null ? String(strategy.zoneId) : null },
        { label: 'Mix item', value: yesNo(strategy.mixItem) },
        { label: 'Mix client', value: yesNo(strategy.mixClient) },
        { label: 'Near picking location', value: yesNo(strategy.nearPickingLocation) },
        { label: 'Location sorts', value: strategy.sorts },
        { label: 'Only client location', value: yesNo(strategy.onlyClientLocation) },
        { label: 'Manual search', value: yesNo(strategy.manualSearch) },
        { label: 'Use area strategy date', value: yesNo(strategy.useAreaStrategyDate) },
        { label: 'Use item data area', value: yesNo(strategy.useItemDataArea) },
      ]}
    />
  );
}

/**
 * Read workspace for a selected strategy (order or storage) -- P4 Task 4, the
 * last screen recompose onto the Control master-detail kit. AttributeGrid
 * covers every backend-owned field (order: the 10-field B5 form's knobs incl.
 * extensionProperties as a mono JSON block when non-empty; storage: the
 * 5-field form's knobs). Edit is write-gated and hands control back to
 * strategies-page.tsx, which swaps the detail slot for the matching form.
 */
export function StrategyDetail(props: Props) {
  if (props.kind === 'order') {
    return (
      <OrderStrategyDetailBody strategy={props.strategy} canWrite={props.canWrite} onEdit={props.onEdit} />
    );
  }
  return (
    <StorageStrategyDetailBody strategy={props.strategy} canWrite={props.canWrite} onEdit={props.onEdit} />
  );
}
