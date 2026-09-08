import { useState } from 'react';
import { Plug, Send, Trash2, Copy, RefreshCw } from 'lucide-react';
import { toast } from 'sonner';
import { cn } from '@/lib/utils';
import {
  MasterDetailLayout,
  MasterList,
  MasterListRow,
} from '@/components/master-detail/master-detail';
import { usePermissions } from '@/hooks/use-permissions';
import {
  useSubscriptions,
  useDeliveries,
  useCreateSubscription,
  useDeleteSubscription,
  useTestSubscription,
  useRedeliver,
} from '@/features/webhooks/use-webhooks';
import type { WebhookSubscription, DeliveryStatus, CreatedSubscription } from '@/types/webhooks';

const VIOLET = '#7C6CCF';

const STATUS_STYLE: Record<DeliveryStatus, { color: string; bg: string }> = {
  PENDING: { color: '#8C836F', bg: '#231F18' },
  DELIVERED: { color: '#7FB77E', bg: 'rgba(127,183,126,0.14)' },
  FAILED: { color: '#E0A45A', bg: 'rgba(224,164,90,0.14)' },
  DEAD: { color: '#E06A5A', bg: 'rgba(224,106,90,0.16)' },
};

export function AdminIntegrationsPage() {
  const { hasPermission } = usePermissions();
  const canWrite = hasPermission('integration-admin');

  const { data: subs = [] } = useSubscriptions();
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [search, setSearch] = useState('');
  const [creating, setCreating] = useState(false);

  const selected = selectedId != null ? (subs.find((s) => s.id === selectedId) ?? null) : null;
  const filtered = subs.filter((s) =>
    s.name.toLowerCase().includes(search.trim().toLowerCase()),
  );

  const activeCount = subs.filter((s) => s.active).length;

  const tiles = [
    { label: 'Active subscriptions', value: String(activeCount) },
    { label: 'Total subscriptions', value: String(subs.length) },
    { label: 'Relay', value: 'OK', lime: true },
  ];

  return (
    <div data-testid="admin-integrations-page">
      <div className="mb-5 flex items-start justify-between">
        <div>
          <h1 className="font-display text-2xl font-bold tracking-[-0.02em] text-foreground">
            Webhooks
          </h1>
          <p className="mt-1 text-[13px] text-muted-foreground">
            Outbound event delivery · subscribe an endpoint, sign with HMAC, retry &amp;
            dead-letter automatically.
          </p>
        </div>
        {canWrite && (
          <button
            type="button"
            onClick={() => {
              setCreating(true);
              setSelectedId(null);
            }}
            className="font-display h-10 rounded-xl bg-primary px-4 text-[13px] font-bold text-primary-foreground"
          >
            + New subscription
          </button>
        )}
      </div>

      {/* Stat strip */}
      <div className="mb-5 flex flex-col overflow-hidden rounded-2xl border border-border bg-card sm:flex-row">
        {tiles.map((t, i) => (
          <div
            key={t.label}
            className={cn(
              'flex-1 px-[18px] py-[15px]',
              i < tiles.length - 1 && 'border-b border-border sm:border-b-0 sm:border-r',
            )}
          >
            <div className="text-[10px] font-semibold uppercase tracking-[0.12em] text-muted-foreground/70">
              {t.label}
            </div>
            <div
              className={cn(
                'numeric mt-2 text-[23px] font-bold',
                t.lime ? 'text-primary' : 'text-foreground',
              )}
            >
              {t.value}
            </div>
          </div>
        ))}
      </div>

      <MasterDetailLayout
        list={
          <MasterList
            searchValue={search}
            onSearchChange={setSearch}
            searchPlaceholder="Search subscriptions…"
          >
            {filtered.map((s) => (
              <MasterListRow
                key={s.id}
                tone="grey"
                active={s.id === selected?.id && !creating}
                onClick={() => {
                  setSelectedId(s.id);
                  setCreating(false);
                }}
              >
                <div className="flex items-center gap-3">
                  <div
                    className="flex size-[34px] flex-none items-center justify-center rounded-[10px] border"
                    style={{ borderColor: 'var(--border)', color: '#A39B86' }}
                  >
                    <Plug className="size-[17px]" />
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="text-[14px] font-semibold text-foreground">{s.name}</div>
                    <div className="numeric mt-0.5 truncate text-[11px] text-muted-foreground/70">
                      {hostOf(s.targetUrl)}
                    </div>
                  </div>
                  <span
                    className="rounded-full px-2 py-0.5 text-[10.5px] font-bold"
                    style={
                      s.active
                        ? { color: VIOLET, background: 'rgba(124,108,207,0.16)' }
                        : { color: '#8C836F', background: '#231F18' }
                    }
                  >
                    {s.active ? 'Active' : 'Paused'}
                  </span>
                </div>
                <div className="numeric mt-2 text-[11px] text-muted-foreground">
                  {s.eventTypes.length} event type(s)
                </div>
              </MasterListRow>
            ))}
          </MasterList>
        }
        detail={
          creating ? (
            <CreateForm onClose={() => setCreating(false)} />
          ) : selected ? (
            <DetailPanel key={selected.id} sub={selected} canWrite={canWrite} />
          ) : (
            <div className="rounded-2xl border border-border bg-card p-8 text-center text-[13px] text-muted-foreground">
              {subs.length === 0
                ? 'No subscriptions yet. Create one to start delivering events.'
                : 'Select a subscription to view its configuration and deliveries.'}
            </div>
          )
        }
      />
    </div>
  );
}

function hostOf(url: string): string {
  try {
    return new URL(url).host;
  } catch {
    return url;
  }
}

// ---------------------------------------------------------------------------
// Create form
// ---------------------------------------------------------------------------

function CreateForm({ onClose }: { onClose: () => void }) {
  const create = useCreateSubscription();
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [events, setEvents] = useState('*');
  const [secret, setSecret] = useState<string | null>(null);

  async function submit() {
    const eventTypes = events
      .split(',')
      .map((e) => e.trim())
      .filter(Boolean);
    if (!name || !url || eventTypes.length === 0) {
      toast.error('Name, URL and at least one event type are required');
      return;
    }
    try {
      const created: CreatedSubscription = await create.mutateAsync({ name, targetUrl: url, eventTypes });
      setSecret(created.secret);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Failed to create subscription');
    }
  }

  if (secret) {
    return (
      <section className="rounded-2xl border border-border bg-card p-5">
        <h2 className="text-base font-semibold text-foreground">Subscription created</h2>
        <p className="mt-2 text-[12px] text-warning-foreground">
          Copy the signing secret now — it won&apos;t be shown again.
        </p>
        <div className="mt-3 flex items-center gap-2 rounded-xl border border-border bg-background p-3">
          <code className="numeric flex-1 truncate text-[12px] text-foreground">{secret}</code>
          <button
            type="button"
            onClick={() => {
              void navigator.clipboard.writeText(secret);
              toast('Secret copied');
            }}
            className="text-muted-foreground hover:text-foreground"
            aria-label="Copy secret"
          >
            <Copy className="size-4" />
          </button>
        </div>
        <button
          type="button"
          onClick={onClose}
          className="font-display mt-4 h-11 w-full rounded-xl bg-primary text-[14px] font-bold text-primary-foreground"
        >
          Done
        </button>
      </section>
    );
  }

  return (
    <section className="rounded-2xl border border-border bg-card p-5">
      <h2 className="mb-4 text-base font-semibold text-foreground">New subscription</h2>
      <Field label="Name">
        <input
          className={inputCls}
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="Acme ERP"
        />
      </Field>
      <Field label="Target URL">
        <input
          className={inputCls}
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          placeholder="https://acme.internal/wms-hooks"
        />
      </Field>
      <Field label="Event types (comma-separated; * = all, prefix* = prefix)">
        <input
          className={inputCls}
          value={events}
          onChange={(e) => setEvents(e.target.value)}
          placeholder="*"
        />
      </Field>
      <div className="mt-4 flex gap-2.5">
        <button
          type="button"
          onClick={() => void submit()}
          disabled={create.isPending}
          className="font-display h-11 flex-1 rounded-xl bg-primary text-[14px] font-bold text-primary-foreground disabled:opacity-60"
        >
          Create
        </button>
        <button
          type="button"
          onClick={onClose}
          className="font-display h-11 rounded-xl border border-border px-4 text-[13px] text-muted-foreground"
        >
          Cancel
        </button>
      </div>
    </section>
  );
}

// ---------------------------------------------------------------------------
// Detail panel
// ---------------------------------------------------------------------------

function DetailPanel({ sub, canWrite }: { sub: WebhookSubscription; canWrite: boolean }) {
  const del = useDeleteSubscription();
  const test = useTestSubscription();
  const redeliver = useRedeliver();
  const [statusFilter, setStatusFilter] = useState('');
  const { data: deliveries = [] } = useDeliveries({
    subscriptionId: sub.id,
    status: statusFilter || undefined,
  });

  return (
    <section className="rounded-2xl border border-border bg-card p-5">
      <div className="mb-4 flex items-start justify-between">
        <div>
          <h2 className="text-base font-semibold text-foreground">{sub.name}</h2>
          <p className="numeric mt-1 text-[11px] text-muted-foreground/70">{sub.targetUrl}</p>
        </div>
        {canWrite && (
          <div className="flex gap-2">
            <button
              type="button"
              onClick={() =>
                test.mutate(sub.id, { onSuccess: () => toast('Test event queued') })
              }
              className="flex h-9 items-center gap-1.5 rounded-lg border px-3 text-[12px]"
              style={{ color: VIOLET, borderColor: '#393056' }}
            >
              <Send className="size-3.5" /> Send test
            </button>
            <button
              type="button"
              onClick={() =>
                del.mutate(sub.id, { onSuccess: () => toast('Subscription deleted') })
              }
              className="flex h-9 items-center gap-1.5 rounded-lg border border-border px-3 text-[12px] text-destructive"
            >
              <Trash2 className="size-3.5" /> Delete
            </button>
          </div>
        )}
      </div>

      {/* Event-type chips */}
      <div className="mb-3 flex flex-wrap gap-1.5">
        {sub.eventTypes.map((e) => (
          <span
            key={e}
            className="numeric rounded-md border border-border bg-background px-2 py-1 text-[11px] text-foreground/75"
          >
            {e}
          </span>
        ))}
      </div>

      {/* Deliveries */}
      <div className="mb-2 mt-5 flex items-center justify-between">
        <div className="numeric text-[10px] font-semibold uppercase tracking-[0.12em] text-muted-foreground/70">
          Recent deliveries
        </div>
        <select
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value)}
          className="rounded-md border border-border bg-background px-2 py-1 text-[12px] text-foreground"
        >
          <option value="">All</option>
          <option value="FAILED">Failed</option>
          <option value="DEAD">Dead</option>
        </select>
      </div>

      <div className="flex flex-col gap-1.5">
        {deliveries.length === 0 && (
          <div className="rounded-lg border border-border bg-background p-3 text-[12px] text-muted-foreground">
            No deliveries yet.
          </div>
        )}
        {deliveries.map((d) => {
          const st = STATUS_STYLE[d.status];
          return (
            <div
              key={d.id}
              className="flex items-center gap-3 rounded-lg border border-border bg-background px-3 py-2"
            >
              <span
                className="rounded-full px-2 py-0.5 text-[10px] font-bold"
                style={{ color: st.color, background: st.bg }}
              >
                {d.status}
              </span>
              <span className="numeric flex-1 truncate text-[12px] text-foreground/75">
                {d.eventType}
              </span>
              <span className="numeric text-[11px] text-muted-foreground/70">
                {d.lastResponseCode ?? '—'} · {d.attempts}x
              </span>
              {canWrite && (d.status === 'FAILED' || d.status === 'DEAD') && (
                <button
                  type="button"
                  onClick={() =>
                    redeliver.mutate(d.id, { onSuccess: () => toast('Re-queued') })
                  }
                  className="text-muted-foreground hover:text-foreground"
                  aria-label="Redeliver"
                >
                  <RefreshCw className="size-3.5" />
                </button>
              )}
            </div>
          );
        })}
      </div>
    </section>
  );
}

// ---------------------------------------------------------------------------
// Micro helpers
// ---------------------------------------------------------------------------

const inputCls =
  'h-10 w-full rounded-xl border border-border bg-background px-3 text-[13px] text-foreground';

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="mb-3">
      <div className="numeric mb-1.5 text-[10px] font-semibold uppercase tracking-[0.12em] text-muted-foreground/70">
        {label}
      </div>
      {children}
    </div>
  );
}
