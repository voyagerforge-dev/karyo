import { Mail, Hash } from 'lucide-react';
import { toast } from 'sonner';
import { cn } from '@/lib/utils';
import {
  effectiveStatus,
  SEVERITY_HEX,
  STATUS_LABEL,
  STATUS_PILL_BG,
  STATUS_PILL_TEXT,
  type Channels,
  type MonitorsController,
  type Operator,
  type Severity,
} from '@/pages/monitors/use-monitors';

interface RuleBuilderProps {
  controller: MonitorsController;
}

const SECTION_LABEL =
  'numeric mb-[9px] text-[10px] font-semibold tracking-[0.12em] text-muted-foreground/70';

const SEVERITIES: Severity[] = ['Low', 'Medium', 'High'];

// `push` stays in the `Channels`/`MonitorChannels` wire type (backend DTO
// compat) but has no UI: webhook delivery is subscription-driven off the
// unconditional `alert.fired` outbox event, not a per-monitor toggle — a
// toggle here would be a lie. Only email/slack are real per-monitor knobs
// (Task 8: `AlertDeliveryChannel` beans keyed off this same `channels` map).
const CHANNELS: Array<{ key: keyof Channels; label: string; Icon: typeof Mail }> = [
  { key: 'email', label: 'Email', Icon: Mail },
  { key: 'slack', label: 'Slack', Icon: Hash },
];

/**
 * Rule builder (right column). Every control writes back to the selected
 * monitor via the controller: WHEN (operator segmented + threshold stepper),
 * SEVERITY segmented, NOTIFY channel toggles (email/slack — live, PATCH
 * `channels`). Save / Delete emit toasts: there is no dedicated save/delete
 * endpoint (every field auto-saves on change; monitors are catalog-defined,
 * not created/removed).
 */
export function RuleBuilder({ controller }: RuleBuilderProps) {
  const m = controller.selected;
  const status = effectiveStatus(m);

  return (
    <section className="min-w-0 flex-1 rounded-2xl border border-border bg-card p-5">
      <div className="mb-1 flex items-center justify-between gap-2">
        <h2 className="m-0 text-[16px] font-semibold text-foreground">{m.name}</h2>
        <span
          className={cn(
            'rounded-[20px] px-2.5 py-[3px] text-[11px] font-bold',
            STATUS_PILL_TEXT[status],
            STATUS_PILL_BG[status],
          )}
        >
          {STATUS_LABEL[status]}
        </span>
      </div>
      <p className="numeric m-0 mb-[18px] text-[11px] text-muted-foreground/70">RULE BUILDER</p>

      {/* WHEN */}
      <div className={SECTION_LABEL}>WHEN</div>
      <div className="mb-[18px] rounded-xl border border-border bg-background p-3.5">
        <div className="text-[13.5px] font-medium text-foreground">{m.metric}</div>
        <div className="mt-3 flex items-center gap-2.5">
          {/* Operator segmented */}
          <div className="flex gap-[3px] rounded-[9px] border border-border bg-card p-[3px]">
            {(['<', '>'] as Operator[]).map((op) => (
              <button
                key={op}
                type="button"
                onClick={() => controller.setOp(op)}
                className={cn(
                  'numeric h-8 w-10 rounded-[7px] text-[15px] font-bold transition-colors',
                  m.op === op
                    ? 'bg-primary text-primary-foreground'
                    : 'bg-transparent text-muted-foreground hover:text-foreground',
                )}
              >
                {op}
              </button>
            ))}
          </div>

          {/* Threshold stepper */}
          <div className="ml-auto flex items-center gap-2.5">
            <button
              type="button"
              onClick={() => controller.stepThreshold(-1)}
              aria-label="Decrease threshold"
              className="h-[38px] w-[38px] flex-none rounded-[10px] border border-border bg-card text-[20px] leading-none text-foreground transition-colors hover:bg-accent"
            >
              −
            </button>
            <div className="min-w-[74px] text-center">
              <span className="numeric text-[24px] font-bold text-primary">{m.threshold}</span>
              <span className="numeric text-[13px] text-muted-foreground/80">{m.unit}</span>
            </div>
            <button
              type="button"
              onClick={() => controller.stepThreshold(1)}
              aria-label="Increase threshold"
              className="h-[38px] w-[38px] flex-none rounded-[10px] border border-border bg-card text-[20px] leading-none text-foreground transition-colors hover:bg-accent"
            >
              +
            </button>
          </div>
        </div>
      </div>

      {/* SEVERITY */}
      <div className={SECTION_LABEL}>SEVERITY</div>
      <div className="mb-[18px] flex gap-2">
        {SEVERITIES.map((sev) => {
          const active = m.severity === sev;
          return (
            <button
              key={sev}
              type="button"
              onClick={() => controller.setSeverity(sev)}
              className={cn(
                'h-10 flex-1 rounded-[10px] text-[13px] font-semibold transition-colors',
                active ? '' : 'bg-background text-muted-foreground ring-1 ring-inset ring-border',
              )}
              style={
                active
                  ? {
                      color: SEVERITY_HEX[sev],
                      backgroundColor: `${SEVERITY_HEX[sev]}1F`,
                      boxShadow: `inset 0 0 0 1.5px ${SEVERITY_HEX[sev]}`,
                    }
                  : undefined
              }
            >
              {sev}
            </button>
          );
        })}
      </div>

      {/* NOTIFY */}
      <div className={SECTION_LABEL}>NOTIFY</div>
      <div className="mb-2 flex gap-2">
        {CHANNELS.map(({ key, label, Icon }) => {
          const on = m.ch[key];
          return (
            <button
              key={key}
              type="button"
              onClick={() => controller.toggleChannel(key)}
              className={cn(
                'flex h-[42px] flex-1 items-center justify-center gap-[7px] rounded-[11px] text-[13px] font-semibold transition-colors',
                on
                  ? 'bg-signal/10 text-primary shadow-[inset_0_0_0_1.5px_var(--acc-color)]'
                  : 'bg-background text-muted-foreground shadow-[inset_0_0_0_1px_var(--border)]',
              )}
            >
              <Icon className="h-3.5 w-3.5" strokeWidth={2} />
              {label}
            </button>
          );
        })}
      </div>
      <div className="mb-[18px] text-[11px] text-muted-foreground/70">
        {(m.ch.email || m.ch.slack) && 'Recipients come from Admin → System properties'}
      </div>

      {/* Actions */}
      <div className="flex gap-2.5">
        <button
          type="button"
          onClick={() =>
            toast('Monitor saved', {
              description: `${m.name} — every field above already auto-saves on change; there is no separate save endpoint.`,
            })
          }
          className="h-11 flex-1 rounded-[11px] bg-primary text-[14px] font-bold text-primary-foreground transition-opacity hover:opacity-90"
        >
          Save monitor
        </button>
        <button
          type="button"
          onClick={() =>
            toast('Delete monitor', {
              description: `${m.name} is catalog-defined; monitors can be disabled, not deleted.`,
            })
          }
          className="h-11 rounded-[11px] border border-destructive/20 bg-transparent px-4 text-[14px] font-medium text-destructive transition-colors hover:bg-destructive/8"
        >
          Delete
        </button>
      </div>
    </section>
  );
}
