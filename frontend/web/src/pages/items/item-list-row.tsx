import { Box } from 'lucide-react';
import { MasterListRow } from '@/components/master-detail/master-detail';
import { ClassChip } from '@/components/control/class-chip';
import { availColor, availTone, type ItemView } from './item-model';

/** A single item row in the master list: icon · SKU + class chip · name · avail. */
export function ItemListRow({
  item,
  active,
  onSelect,
}: {
  item: ItemView;
  active: boolean;
  onSelect: () => void;
}) {
  const available = item.stock?.available ?? null;
  const reorderPoint = item.forecast?.suggestedReorderPoint ?? null;

  return (
    <MasterListRow tone={availTone(available, reorderPoint)} active={active} onClick={onSelect}>
      <div className="flex items-center gap-3">
        <div className="flex size-[42px] flex-none items-center justify-center rounded-[10px] border border-border bg-background text-muted-foreground/70">
          <Box className="size-5" strokeWidth={1.6} />
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="numeric text-[12.5px] font-semibold text-foreground">{item.sku}</span>
            <ClassChip cls={item.cls} size="sm" />
          </div>
          <div className="mt-0.5 truncate text-[12.5px] text-foreground/70">{item.name}</div>
        </div>
        <div className="text-right">
          <div className="numeric text-[13px] font-bold" style={{ color: availColor(available, reorderPoint) }}>
            {available != null ? available.toLocaleString() : '—'}
          </div>
          <div className="mt-px text-[10.5px] text-muted-foreground/70">avail</div>
        </div>
      </div>
    </MasterListRow>
  );
}
