import type { EntityTone } from '@/components/master-detail/tones';
import { TONE_COLOR } from '@/components/master-detail/tones';

export interface StatusPillProps {
  label: string;
  tone: EntityTone;
}

/** Rounded-full status pill: tone text on a ~12%-opacity tint of the same tone. */
export function StatusPill({ label, tone }: StatusPillProps) {
  const color = TONE_COLOR[tone];
  return (
    <span
      className="inline-flex items-center rounded-full px-[9px] py-[2px] text-[11px] font-bold"
      style={{ color, background: `${color}1f` }}
    >
      {label}
    </span>
  );
}
