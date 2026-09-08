import { Lock } from 'lucide-react';
import { Sheet, SheetContent } from '@/components/ui/sheet';
import { cn } from '@/lib/utils';
import { useControlPrefsContext } from '@/pages/home/ops/control-prefs-context';
import type { Accent } from '@/pages/home/ops/use-control-prefs';
import type { Density } from '@/pages/home/ops/use-density';
import type { AdminEnv, UseAdminPrefs } from '@/pages/admin/use-admin-prefs';

/**
 * Workspace settings panel (v3) — a right slide-over that productizes the
 * design-time "tweaks" as persisted user prefs. Two variants:
 *  - "console": accent / density (ControlPrefs ctx)
 *  - "admin":   environment / SPI-id visibility on the Strategies catalog
 *
 * Reused from both the Console topbar (AppHeader) and the Admin topbar
 * (AdminShell). State is shared with the surface it personalizes — the console
 * variant writes the ControlPrefs context the Operations Control screen reads;
 * the admin variant receives the SAME `useAdminPrefs` instance AdminShell holds
 * (passed via `adminPrefs`), so the env pill + non-prod banner update live.
 */
interface WorkspacePanelProps {
  variant: 'console' | 'admin';
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Required for the admin variant — the shared admin-prefs instance. */
  adminPrefs?: UseAdminPrefs;
}

/** Section label — JetBrains Mono micro-caps, dim. */
function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <div className="numeric mb-3 text-[10px] font-semibold uppercase tracking-[0.12em] text-muted-foreground/70">
      {children}
    </div>
  );
}

/** A segmented control built from plain buttons (no toggle-group component). */
function Segmented<T extends string | boolean>({
  options,
  value,
  onChange,
}: {
  options: { label: string; value: T }[];
  value: T;
  onChange: (next: T) => void;
}) {
  return (
    <div className="flex gap-1.5">
      {options.map((o) => {
        const active = o.value === value;
        return (
          <button
            key={String(o.value)}
            type="button"
            onClick={() => onChange(o.value)}
            className={cn(
              'h-[34px] flex-1 rounded-lg text-[12px] font-semibold transition-colors',
              active
                ? 'bg-primary text-primary-foreground'
                : 'bg-background text-muted-foreground ring-1 ring-inset ring-border hover:text-foreground',
            )}
          >
            {o.label}
          </button>
        );
      })}
    </div>
  );
}

const ACCENT_SWATCHES: { value: Accent; label: string; hex: string }[] = [
  { value: 'lime', label: 'Lime', hex: 'rgb(199 242 78)' },
  { value: 'cyan', label: 'Cyan', hex: '#38CDE6' },
  { value: 'amber', label: 'Amber', hex: '#F0B43C' },
  { value: 'violet', label: 'Violet', hex: '#A78BFA' },
];

const DENSITY_OPTS: { label: string; value: Density }[] = [
  { label: 'Comfortable', value: 'comfortable' },
  { label: 'Command', value: 'command' },
];

const ENV_OPTS: { label: string; value: AdminEnv }[] = [
  { label: 'Production', value: 'Production' },
  { label: 'Staging', value: 'Staging' },
  { label: 'Sandbox', value: 'Sandbox' },
];

const SPI_OPTS: { label: string; value: boolean }[] = [
  { label: 'Show', value: true },
  { label: 'Hide', value: false },
];

function ConsoleBody() {
  const prefs = useControlPrefsContext();
  return (
    <>
      <SectionLabel>Accent signal</SectionLabel>
      <div className="mb-6 flex gap-3.5">
        {ACCENT_SWATCHES.map((s) => {
          const selected = prefs.accent === s.value;
          return (
            <button
              key={s.value}
              type="button"
              aria-label={s.label}
              aria-pressed={selected}
              onClick={() => prefs.set('accent', s.value)}
              className="size-9 cursor-pointer rounded-[10px] outline-none transition-shadow"
              style={{
                background: s.hex,
                // selected = a 2px gap ring in the swatch's own color (matches
                // the prototype); unselected = a thin neutral border ring.
                boxShadow: selected
                  ? `0 0 0 2px var(--card), 0 0 0 4px ${s.hex}`
                  : '0 0 0 1px var(--border)',
              }}
            />
          );
        })}
      </div>

      <SectionLabel>Density</SectionLabel>
      <div>
        <Segmented
          options={DENSITY_OPTS}
          value={prefs.density}
          onChange={(v) => prefs.set('density', v)}
        />
      </div>
    </>
  );
}

function AdminBody({ prefs }: { prefs: UseAdminPrefs }) {
  return (
    <>
      <SectionLabel>Environment</SectionLabel>
      <Segmented options={ENV_OPTS} value={prefs.env} onChange={(v) => prefs.set('env', v)} />
      <p className="mb-6 mt-2.5 text-[11.5px] text-muted-foreground/70">
        Switches the config target. Non-production shows a banner.
      </p>

      <SectionLabel>SPI interface IDs</SectionLabel>
      <Segmented
        options={SPI_OPTS}
        value={prefs.showSpiIds}
        onChange={(v) => prefs.set('showSpiIds', v)}
      />
      <p className="mt-2.5 text-[11.5px] text-muted-foreground/70">
        Reveal technical interface identifiers on the Strategies catalog.
      </p>
    </>
  );
}

export function WorkspacePanel({ variant, open, onOpenChange, adminPrefs }: WorkspacePanelProps) {
  const subtitle = variant === 'console' ? 'PERSONALIZE THIS CONSOLE' : 'ADMIN PREFERENCES';

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent
        side="right"
        showCloseButton
        // ~330px panel, dark card surface; gap-0 so our sections control spacing.
        className="w-[330px] gap-0 border-border bg-card p-0 sm:max-w-[330px]"
      >
        {/* Header */}
        <div className="flex items-center gap-2.5 border-b border-border px-5 py-[18px]">
          <SlidersIcon variant={variant} />
          <div className="leading-tight">
            <div className="text-[15px] font-semibold text-foreground">Workspace</div>
            <div className="numeric text-[10px] uppercase tracking-[0.06em] text-muted-foreground/70">
              {subtitle}
            </div>
          </div>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto p-5">
          {variant === 'console' || !adminPrefs ? <ConsoleBody /> : <AdminBody prefs={adminPrefs} />}
        </div>

        {/* Footer */}
        <div className="flex items-center gap-2 border-t border-border px-5 py-3.5">
          <Lock className="size-3.5 text-muted-foreground/70" />
          <span className="numeric text-[10.5px] text-muted-foreground/70">Saved to this device</span>
        </div>
      </SheetContent>
    </Sheet>
  );
}

/** Sliders glyph — accent on console (lime via --acc), violet on admin chrome. */
function SlidersIcon({ variant }: { variant: 'console' | 'admin' }) {
  return (
    <svg
      width="17"
      height="17"
      viewBox="0 0 24 24"
      fill="none"
      stroke={variant === 'admin' ? '#A78BFA' : 'var(--acc-color, var(--primary))'}
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      <path d="M20 7h-9" />
      <path d="M14 17H5" />
      <circle cx="17" cy="7" r="3" />
      <circle cx="7" cy="17" r="3" />
    </svg>
  );
}
