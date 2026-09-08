import { useState } from 'react';
import { toast } from 'sonner';
import { Switch } from '@/components/ui/switch';
import { SectionCard } from '@/components/control/section-card';
import { StatusPill } from '@/components/control/status-pill';
import type { EntityTone } from '@/components/master-detail/tones';
import { usePermissions } from '@/hooks/use-permissions';
import {
  useSystemProperties,
  useSetSystemProperty,
  useResetSystemProperty,
} from '@/pages/admin/use-system-properties';
import type { SystemPropertyView } from '@/types/system-property';

const CUSTOM_GROUP = 'Custom';

const SOURCE_TONE: Record<SystemPropertyView['source'], EntityTone> = {
  CLIENT: 'lime',
  SYSTEM: 'blue',
  CONFIG: 'amber',
  DEFAULT: 'grey',
};

/**
 * Admin -> System properties (SC16 frontend). Renders the effective view returned by
 * `GET /api/v1/system-properties` grouped by catalog `group` (stored non-catalog rows,
 * which carry no group, are bucketed under "Custom"). A stored row (`source` CLIENT or
 * SYSTEM) offers "Reset to default" (DELETE), which reverts to the CONFIG/DEFAULT
 * fallback. `ownerWritable: false` keys are not disabled here -- there is no
 * principal-kind signal on the frontend beyond permissions, so the honest UX is to let
 * the attempt go through and let the 403 (`system-property-owner-forbidden`) surface as
 * the usual toast via `api-client`; an "Ops-controlled" badge hints at the restriction
 * up front. Write controls check `user-admin`, matching SystemPropertyResource's own
 * write RBAC (A10, row :1420).
 */
export function AdminPropertiesPage() {
  const { hasPermission } = usePermissions();
  const canWrite = hasPermission('user-admin');

  const { data: properties = [] } = useSystemProperties();
  const groups = groupByCategory(properties);

  return (
    <div data-testid="admin-properties-page">
      <div className="mb-5">
        <h1 className="font-display text-2xl font-bold tracking-[-0.02em] text-foreground">
          System properties
        </h1>
        <p className="mt-1 text-[13px] text-muted-foreground">
          Runtime config store · catalog defaults, live env/config overrides, and any
          stored overrides — client-specific rows win over instance-wide ones.
        </p>
      </div>

      {groups.length === 0 ? (
        <div className="rounded-2xl border border-border bg-card p-8 text-center text-[13px] text-muted-foreground">
          No properties to show.
        </div>
      ) : (
        <div className="flex flex-col gap-5">
          {groups.map(([groupName, props]) => (
            <SectionCard key={groupName} title={groupName}>
              <div className="flex flex-col gap-2">
                {props.map((p) => (
                  <PropertyRow
                    key={p.context ? `${p.key}:${p.context}` : p.key}
                    prop={p}
                    canWrite={canWrite}
                  />
                ))}
              </div>
            </SectionCard>
          ))}
        </div>
      )}
    </div>
  );
}

/** Groups by catalog `group`; non-catalog stored rows (no group) go under "Custom" (last). */
function groupByCategory(
  properties: SystemPropertyView[],
): [string, SystemPropertyView[]][] {
  const byGroup = new Map<string, SystemPropertyView[]>();
  for (const p of properties) {
    const name = p.group ?? CUSTOM_GROUP;
    const list = byGroup.get(name) ?? [];
    list.push(p);
    byGroup.set(name, list);
  }
  const names = [...byGroup.keys()].sort((a, b) => {
    if (a === CUSTOM_GROUP) return 1;
    if (b === CUSTOM_GROUP) return -1;
    return a.localeCompare(b);
  });
  return names.map((name) => [name, byGroup.get(name)!]);
}

// ---------------------------------------------------------------------------
// Row
// ---------------------------------------------------------------------------

function PropertyRow({ prop, canWrite }: { prop: SystemPropertyView; canWrite: boolean }) {
  const setProp = useSetSystemProperty();
  const resetProp = useResetSystemProperty();
  const isStored = prop.source === 'CLIENT' || prop.source === 'SYSTEM';
  const isBoolean = prop.type === 'BOOLEAN';

  const [draft, setDraft] = useState(prop.secret ? '' : (prop.value ?? ''));
  const [overrideOpen, setOverrideOpen] = useState(false);
  const [overrideContext, setOverrideContext] = useState('');
  const [overrideValue, setOverrideValue] = useState('');

  async function submit(value: string) {
    try {
      await setProp.mutateAsync({
        key: prop.key,
        body: { value, context: prop.context ?? undefined },
      });
      toast(`${prop.key} updated`);
      if (prop.secret) setDraft('');
    } catch (err) {
      toast.error(err instanceof Error ? err.message : `Failed to update ${prop.key}`);
    }
  }

  async function reset() {
    try {
      await resetProp.mutateAsync({ key: prop.key, context: prop.context ?? undefined });
      toast(`${prop.key} reset to default`);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : `Failed to reset ${prop.key}`);
    }
  }

  /** Row 33: a context-scoped override is a NEW row (a distinct `{key, context}` stored
   *  value), not an edit of this one -- same PUT the plain editor above uses, just with
   *  an explicit context instead of `prop.context`. */
  async function submitOverride() {
    const context = overrideContext.trim();
    try {
      await setProp.mutateAsync({ key: prop.key, body: { value: overrideValue, context } });
      toast(`${prop.key} override added for "${context}"`);
      setOverrideOpen(false);
      setOverrideContext('');
      setOverrideValue('');
    } catch (err) {
      toast.error(err instanceof Error ? err.message : `Failed to add an override for ${prop.key}`);
    }
  }

  const busy = setProp.isPending || resetProp.isPending;

  return (
    <div
      data-testid={`property-row-${prop.key}`}
      className="flex flex-col gap-2.5 rounded-xl border border-border bg-background px-[13px] py-[11px]"
    >
      <div className="flex flex-col gap-2.5 sm:flex-row sm:items-center sm:justify-between sm:gap-4">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="numeric text-[13px] font-semibold text-foreground">{prop.key}</span>
            {prop.context && (
              <span data-testid={`property-context-badge-${prop.key}`}>
                <StatusPill label={`context: ${prop.context}`} tone="grey" />
              </span>
            )}
            <StatusPill label={prop.source} tone={SOURCE_TONE[prop.source]} />
            {!prop.ownerWritable && <StatusPill label="Ops-controlled" tone="violet" />}
          </div>
          {prop.description && (
            <p className="mt-1 text-[12px] text-muted-foreground/80">{prop.description}</p>
          )}
        </div>

        <div className="flex flex-none items-center gap-2">
          {prop.secret ? (
            <>
              <span className="numeric text-[12px] text-muted-foreground/70">
                {prop.value ?? 'Not set'}
              </span>
              {canWrite && (
                <>
                  <input
                    type="password"
                    className={inputCls}
                    value={draft}
                    onChange={(e) => setDraft(e.target.value)}
                    placeholder="Enter new value"
                    aria-label={`New value for ${prop.key}`}
                    disabled={busy}
                  />
                  <button
                    type="button"
                    onClick={() => void submit(draft)}
                    disabled={busy || !draft}
                    className="font-display h-9 flex-none rounded-lg border border-border px-3 text-[12px] text-foreground disabled:opacity-60"
                  >
                    Set
                  </button>
                </>
              )}
            </>
          ) : isBoolean ? (
            <Switch
              checked={prop.value === 'true'}
              disabled={!canWrite || busy}
              onCheckedChange={(checked) => void submit(String(checked))}
              aria-label={prop.key}
            />
          ) : (
            <>
              <input
                type={prop.type === 'INTEGER' ? 'number' : 'text'}
                className={inputCls}
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                aria-label={prop.key}
                disabled={!canWrite || busy}
              />
              {canWrite && (
                <button
                  type="button"
                  onClick={() => void submit(draft)}
                  disabled={busy || draft === (prop.value ?? '')}
                  className="font-display h-9 flex-none rounded-lg border border-border px-3 text-[12px] text-foreground disabled:opacity-60"
                >
                  Save
                </button>
              )}
            </>
          )}
          {canWrite && isStored && (
            <button
              type="button"
              onClick={() => void reset()}
              disabled={busy}
              className="font-display h-9 flex-none rounded-lg border border-border px-3 text-[12px] text-muted-foreground disabled:opacity-60"
            >
              Reset to default
            </button>
          )}
          {canWrite && (
            <button
              type="button"
              onClick={() => setOverrideOpen((v) => !v)}
              data-testid={`property-add-override-${prop.key}`}
              className="font-display h-9 flex-none rounded-lg border border-border px-3 text-[12px] text-foreground disabled:opacity-60"
            >
              {overrideOpen ? 'Cancel override' : 'Add context override'}
            </button>
          )}
        </div>
      </div>

      {overrideOpen && (
        <div
          data-testid={`property-override-form-${prop.key}`}
          className="flex flex-wrap items-center gap-2 border-t border-border pt-2.5"
        >
          <input
            type="text"
            className={inputCls}
            placeholder="Context (e.g. client:2)"
            value={overrideContext}
            onChange={(e) => setOverrideContext(e.target.value)}
            aria-label={`Override context for ${prop.key}`}
            disabled={busy}
          />
          <input
            type="text"
            className={inputCls}
            placeholder="Value"
            value={overrideValue}
            onChange={(e) => setOverrideValue(e.target.value)}
            aria-label={`Override value for ${prop.key}`}
            disabled={busy}
          />
          <button
            type="button"
            onClick={() => void submitOverride()}
            disabled={busy || !overrideContext.trim() || !overrideValue.trim()}
            data-testid={`property-override-submit-${prop.key}`}
            className="font-display h-9 flex-none rounded-lg border border-border px-3 text-[12px] text-foreground disabled:opacity-60"
          >
            Save override
          </button>
        </div>
      )}
    </div>
  );
}

const inputCls =
  'h-9 w-[180px] rounded-lg border border-border bg-card px-2.5 text-[12.5px] text-foreground disabled:opacity-60';
