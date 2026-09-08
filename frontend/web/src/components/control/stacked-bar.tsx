export interface StackedBarSegment {
  value: number;
  color: string;
  label: string;
}

export interface StackedBarProps {
  segments: StackedBarSegment[];
}

/** 14px-tall, 7px-radius stacked bar (e.g. available/allocated/held); 0 total renders all zero-width. */
export function StackedBar({ segments }: StackedBarProps) {
  const total = segments.reduce((sum, s) => sum + s.value, 0);
  return (
    <div className="flex h-[14px] gap-[2px] overflow-hidden rounded-[7px]">
      {segments.map((s) => (
        <div
          key={s.label}
          title={s.label}
          style={{ width: `${total === 0 ? 0 : (s.value / total) * 100}%`, background: s.color }}
        />
      ))}
    </div>
  );
}
