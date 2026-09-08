import { Sparkles } from 'lucide-react';
import type { Monitor } from '@/pages/monitors/use-monitors';

interface FiringBandProps {
  firing: Monitor[];
  onApply: (id: string) => void;
  onMute: (id: string) => void;
}

/**
 * "Firing now" band — a pulsing red header plus one card per firing monitor.
 * Each card shows the fired condition, a Copilot-fix inset, and Apply fix
 * (clears to HEALTHY) / Mute (disables) actions.
 */
export function FiringBand({ firing, onApply, onMute }: FiringBandProps) {
  if (firing.length === 0) return null;

  return (
    <section className="mb-[var(--secmb,20px)]">
      <div className="mb-3 flex items-center gap-[9px]">
        <span className="relative flex h-2 w-2 flex-none">
          <span className="absolute inline-flex h-full w-full rounded-full bg-error opacity-70 motion-safe:animate-ping" />
          <span className="relative inline-flex h-2 w-2 rounded-full bg-error" />
        </span>
        <h2 className="m-0 text-[14px] font-semibold text-foreground">Firing now</h2>
        <span className="numeric text-[11px] text-muted-foreground">
          {firing.length} active · Copilot has a fix ready
        </span>
      </div>

      <div className="flex flex-col gap-3.5 lg:flex-row">
        {firing.map((m) => (
          <div
            key={m.id}
            className="min-w-0 flex-1 rounded-[14px] border border-destructive/30 p-4"
            style={{ background: 'linear-gradient(180deg, var(--error) 0%, var(--card) 60%)' }}
          >
            <div className="mb-2.5 flex items-center gap-2">
              <span className="h-[7px] w-[7px] flex-none rounded-sm bg-destructive" />
              <span className="numeric text-[11px] font-bold tracking-[0.04em] text-foreground">
                {m.name}
              </span>
              <span className="numeric ml-auto text-[10.5px] text-muted-foreground">{m.last}</span>
            </div>

            <div className="mb-3 text-[12.5px] text-foreground/75">{m.fired}</div>

            {/* Copilot fix inset */}
            <div className="mb-3 flex items-start gap-[9px] rounded-[11px] border border-signal/25 bg-background p-[11px]">
              <Sparkles
                className="mt-px h-[15px] w-[15px] flex-none text-primary"
                strokeWidth={2}
              />
              <div className="text-[12.5px] leading-[1.45] text-foreground/85">
                <span className="numeric text-[10px] tracking-[0.08em] text-primary">COPILOT</span>
                <br />
                {m.fix}
              </div>
            </div>

            <div className="flex gap-2">
              <button
                type="button"
                onClick={() => onApply(m.id)}
                className="h-[34px] flex-1 rounded-[8px] bg-primary text-[12.5px] font-bold text-primary-foreground transition-opacity hover:opacity-90"
              >
                Apply fix
              </button>
              <button
                type="button"
                onClick={() => onMute(m.id)}
                className="h-[34px] rounded-[8px] border border-border bg-transparent px-3.5 text-[12.5px] font-medium text-muted-foreground transition-colors hover:bg-accent"
              >
                Mute
              </button>
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}
