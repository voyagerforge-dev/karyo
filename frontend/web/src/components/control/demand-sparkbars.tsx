export interface DemandSparkbar {
  pct: number;
  color: string;
}

export interface DemandSparkbarsProps {
  bars: DemandSparkbar[] | null | undefined;
}

/** 90px-tall demand sparkbar row; null/empty bars render an honest "no data yet" empty-state at the same footprint. */
export function DemandSparkbars({ bars }: DemandSparkbarsProps) {
  if (!bars || bars.length === 0) {
    return (
      <div className="flex h-[90px] items-center justify-center text-[12.5px] text-muted-foreground/70">
        No demand history yet
      </div>
    );
  }
  return (
    <div className="flex h-[90px] items-end gap-[5px]">
      {bars.map((b, i) => (
        <div
          key={i}
          data-bar
          className="flex-1"
          style={{ height: `${b.pct}%`, background: b.color, borderRadius: '3px 3px 1px 1px' }}
        />
      ))}
    </div>
  );
}
