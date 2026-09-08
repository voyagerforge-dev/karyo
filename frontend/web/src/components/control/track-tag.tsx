import { cn } from '@/lib/utils';

export interface TrackTagProps {
  tracked: boolean;
}

/** LPN/LOOSE storage-track badge shown next to a stored-location's unit-load type. */
export function TrackTag({ tracked }: TrackTagProps) {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-[5px] px-[6px] py-[1px] font-mono text-[9.5px] font-bold tracking-[0.04em]',
        tracked ? 'bg-background text-muted-foreground' : 'bg-transparent text-muted-foreground/60',
      )}
    >
      {tracked ? 'LPN' : 'LOOSE'}
    </span>
  );
}
