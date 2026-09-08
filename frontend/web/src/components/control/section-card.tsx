import type { ReactNode } from 'react';
import { cn } from '@/lib/utils';

export interface SectionCardProps {
  title?: string;
  action?: ReactNode;
  noPad?: boolean;
  children: ReactNode;
}

/** bg-card panel used to frame every detail sub-section; optional header row w/ divider. */
export function SectionCard({ title, action, noPad, children }: SectionCardProps) {
  return (
    <div className="overflow-hidden rounded-2xl border border-border bg-card">
      {title && (
        <div className="flex items-center justify-between border-b border-border px-[18px] py-[14px]">
          <h2 className="m-0 text-sm font-semibold text-foreground">{title}</h2>
          {action}
        </div>
      )}
      <div className={cn(!noPad && 'p-5')}>{children}</div>
    </div>
  );
}
