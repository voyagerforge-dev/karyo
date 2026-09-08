import { cn } from '@/lib/utils';

const MAP = {
  A: { color: 'var(--acc-color)', bg: 'var(--acc-soft)' },
  B: { color: '#5AB7E0', bg: 'rgba(90,183,224,0.14)' },
  C: { color: '#7C6CCF', bg: 'rgba(124,108,207,0.16)' },
} as const;

export interface ClassChipProps {
  cls: 'A' | 'B' | 'C' | null;
  size?: 'sm' | 'md';
}

/** Square rounded ABC-velocity chip; null renders a neutral dash. */
export function ClassChip({ cls, size = 'md' }: ClassChipProps) {
  const s = cls ? MAP[cls] : { color: '#6E6655', bg: '#231F18' };
  const px = size === 'sm' ? 'size-[18px] rounded-[5px] text-[10px]' : 'size-6 rounded-[7px] text-[12px]';
  return (
    <span
      className={cn('flex flex-none items-center justify-center font-mono font-extrabold', px)}
      style={{ color: s.color, background: s.bg }}
    >
      {cls ?? '—'}
    </span>
  );
}
