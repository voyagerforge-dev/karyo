export interface AttributeGridItem {
  label: string;
  value: string | null;
}

export interface AttributeGridProps {
  items: AttributeGridItem[];
}

/** 2-col grid of uppercase micro-label + mono value cells; null values render as a dash. */
export function AttributeGrid({ items }: AttributeGridProps) {
  return (
    <div className="grid grid-cols-2 gap-x-[18px] gap-y-[14px]">
      {items.map((item) => (
        <div key={item.label}>
          <div className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground/70">{item.label}</div>
          <div className="mt-[5px] font-mono text-[13px] font-semibold text-foreground/90">{item.value ?? '—'}</div>
        </div>
      ))}
    </div>
  );
}
