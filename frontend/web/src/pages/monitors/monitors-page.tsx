import { Lock } from 'lucide-react';
import { useLicense } from '@/features/license/use-license';
import { useMonitors } from '@/pages/monitors/use-monitors';
import { FiringBand } from '@/pages/monitors/firing-band';
import { MonitorList } from '@/pages/monitors/monitor-list';
import { RuleBuilder } from '@/pages/monitors/rule-builder';
import { AlertFeed } from '@/pages/monitors/alert-feed';
import { AlertDeliveriesPanel } from '@/pages/monitors/alert-deliveries-panel';

/**
 * Event Monitors (Screen 6) — configurable threshold-rule alerting where every
 * firing alert carries a one-click Copilot fix. Renders inside the app shell.
 *
 * This screen is gated behind the `monitors` paid-license entitlement
 * (`useLicense().isEntitled('monitors')`, backed by `GET /api/v1/license` —
 * the gate-discovery endpoint that is never itself license-gated). Without
 * the entitlement, a locked/upsell panel is shown instead of the engine.
 *
 * Layout: header + 3 summary tiles (Active / Firing / Muted) → "Firing now"
 * band → two-column body (monitor list ~1.5 / rule builder ~1) → alert feed
 * → recent deliveries (row 34: `alert_deliveries`, redeliver a stuck row).
 * All controls are live and backed by the real `karyo-monitors` API (see
 * use-monitors.ts / monitors-api.ts / use-alert-deliveries.ts).
 */
const SUMMARY_TILES: Array<{ key: 'active' | 'firing' | 'muted'; label: string; tone: string }> = [
  { key: 'active', label: 'ACTIVE', tone: 'text-primary' },
  { key: 'firing', label: 'FIRING', tone: 'text-destructive' },
  { key: 'muted', label: 'MUTED', tone: 'text-muted-foreground/70' },
];

function LockedMonitorsPanel() {
  return (
    <div
      data-testid="monitors-locked"
      className="flex flex-col items-center justify-center gap-3 rounded-2xl border border-border bg-card px-8 py-20 text-center"
    >
      <div className="flex size-12 items-center justify-center rounded-full bg-signal/10">
        <Lock className="size-5 text-primary" strokeWidth={2} />
      </div>
      <h1 className="font-display m-0 text-[20px] font-bold tracking-[-0.02em] text-foreground">
        Event monitors is a paid add-on
      </h1>
      <p className="m-0 max-w-md text-[13px] text-muted-foreground">
        Threshold rules that watch the floor, fire alerts, and hand Copilot a ready-made fix —
        contact your account team to enable Monitors for this tenant.
      </p>
    </div>
  );
}

function MonitorsEngine() {
  const controller = useMonitors();
  const counts = {
    active: controller.activeCount,
    firing: controller.firingCount,
    muted: controller.mutedCount,
  };

  return (
    <>
      {/* Header + summary tiles */}
      <div className="mb-[18px] flex items-end justify-between gap-4">
        <div>
          <h1 className="font-display m-0 text-[24px] font-bold tracking-[-0.02em] text-foreground">
            Event monitors
          </h1>
          <p className="m-0 mt-[5px] text-[13px] text-muted-foreground">
            Threshold rules that watch the floor and act before it slips.
          </p>
        </div>
        <div className="flex gap-2.5">
          {SUMMARY_TILES.map((t) => (
            <div
              key={t.key}
              className="flex flex-col items-center rounded-xl border border-border bg-card px-[18px] py-2.5"
            >
              <span className={`numeric text-[20px] font-bold ${t.tone}`}>{counts[t.key]}</span>
              <span className="numeric mt-0.5 text-[9.5px] tracking-[0.08em] text-muted-foreground/70">
                {t.label}
              </span>
            </div>
          ))}
        </div>
      </div>

      <FiringBand
        firing={controller.firing}
        onApply={controller.applyFix}
        onMute={controller.mute}
      />

      {/* Body: list + builder */}
      <div className="flex flex-col items-start gap-5 lg:flex-row">
        <MonitorList
          monitors={controller.monitors}
          selectedId={controller.selected.id}
          onSelect={controller.select}
          onToggle={controller.toggleEnabled}
        />
        <RuleBuilder controller={controller} />
      </div>

      <AlertFeed />
      <AlertDeliveriesPanel />
    </>
  );
}

export function MonitorsPage() {
  const license = useLicense();

  let body: React.ReactNode;
  if (license.isLoading) {
    body = <p className="text-[13px] text-muted-foreground">Loading…</p>;
  } else if (!license.isEntitled('monitors')) {
    body = <LockedMonitorsPanel />;
  } else {
    body = <MonitorsEngine />;
  }

  return <div data-testid="monitors-page">{body}</div>;
}
