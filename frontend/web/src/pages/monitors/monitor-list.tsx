import { Mail, Hash } from 'lucide-react';
import { Switch } from '@/components/ui/switch';
import { cn } from '@/lib/utils';
import {
  conditionText,
  effectiveStatus,
  SEVERITY_BG,
  SEVERITY_TEXT,
  STATUS_DOT,
  type Monitor,
} from '@/pages/monitors/use-monitors';

interface MonitorListProps {
  monitors: Monitor[];
  selectedId: string;
  onSelect: (id: string) => void;
  onToggle: (id: string) => void;
}

const GRID = 'grid-cols-[20px_1fr_120px_92px_64px_52px]';

/**
 * Monitor list table (left column). Each row: status dot, name + mono
 * condition, scope, severity pill, channel icons (lime when on / dim off),
 * and an enable toggle. Clicking a row selects it into the builder; the
 * selected row is tinted with the lime soft accent.
 */
export function MonitorList({ monitors, selectedId, onSelect, onToggle }: MonitorListProps) {
  return (
    <section className="min-w-0 flex-1 overflow-hidden rounded-2xl border border-border bg-card lg:flex-[1.5]">
      {/* Header row */}
      <div
        className={cn(
          'grid items-center gap-[14px] border-b border-border px-[18px] py-3',
          GRID,
        )}
      >
        <span />
        <span className="numeric text-[10px] font-semibold tracking-[0.08em] text-muted-foreground/70">
          MONITOR
        </span>
        <span className="numeric text-[10px] font-semibold tracking-[0.08em] text-muted-foreground/70">
          SCOPE
        </span>
        <span className="numeric text-[10px] font-semibold tracking-[0.08em] text-muted-foreground/70">
          SEVERITY
        </span>
        <span className="numeric text-[10px] font-semibold tracking-[0.08em] text-muted-foreground/70">
          ALERT
        </span>
        <span className="numeric text-right text-[10px] font-semibold tracking-[0.08em] text-muted-foreground/70">
          ON
        </span>
      </div>

      {monitors.map((m) => {
        const status = effectiveStatus(m);
        const isSelected = m.id === selectedId;
        return (
          <button
            key={m.id}
            type="button"
            onClick={() => onSelect(m.id)}
            className={cn(
              'grid w-full items-center gap-[14px] border-b border-border px-[18px] py-[13px] text-left transition-colors',
              GRID,
              isSelected ? 'bg-[var(--acc-soft)]' : 'hover:bg-accent',
            )}
          >
            <span className={cn('h-[9px] w-[9px] rounded-full', STATUS_DOT[status])} />

            <div className="min-w-0">
              <div className="truncate text-[13.5px] font-semibold text-foreground">{m.name}</div>
              <div className="numeric mt-0.5 truncate text-[11.5px] text-muted-foreground">
                {conditionText(m)}
              </div>
            </div>

            <span className="truncate text-[12px] text-foreground/70">{m.scope}</span>

            <span
              className={cn(
                'w-fit rounded-[20px] px-[9px] py-[3px] text-[11px] font-bold',
                SEVERITY_TEXT[m.severity],
                SEVERITY_BG[m.severity],
              )}
            >
              {m.severity}
            </span>

            {/* Push dropped: it has no per-monitor backend (webhook delivery is
                subscription-driven, unconditional — see rule-builder.tsx). */}
            <div className="flex gap-[5px]">
              <Mail
                data-testid="channel-email"
                className={cn('h-3.5 w-3.5', m.ch.email ? 'text-primary' : 'text-muted-foreground/30')}
                strokeWidth={2}
              />
              <Hash
                data-testid="channel-slack"
                className={cn('h-3.5 w-3.5', m.ch.slack ? 'text-primary' : 'text-muted-foreground/30')}
                strokeWidth={2}
              />
            </div>

            <div className="flex justify-end">
              {/* Toggle must not bubble into row select */}
              <span onClick={(e) => e.stopPropagation()}>
                <Switch
                  checked={m.enabled}
                  onCheckedChange={() => onToggle(m.id)}
                  aria-label={`Enable ${m.name}`}
                />
              </span>
            </div>
          </button>
        );
      })}
    </section>
  );
}
