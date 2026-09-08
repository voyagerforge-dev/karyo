import { useState, type ReactNode } from 'react';
import { Briefcase } from 'lucide-react';
import { toast } from 'sonner';
import { cn } from '@/lib/utils';
import {
  MasterDetailLayout,
  MasterList,
  MasterListRow,
} from '@/components/master-detail/master-detail';
import { StatusPill } from '@/components/control/status-pill';
import { AttributeGrid } from '@/components/control/attribute-grid';
import { SectionCard } from '@/components/control/section-card';
import { usePermissions } from '@/hooks/use-permissions';
import {
  useClients,
  useCreateClient,
  useUpdateClient,
  useSetClientActive,
  useConsistencyCheck,
} from '@/features/clients/use-clients';
import type { ClientResponse, CreateClientRequest } from '@/types/client';

/**
 * Goods-owner (Client) administration -- B10-3. Reads are VIEWER+ server-side,
 * but this page lives under /admin (AdminGuard, user-admin-or-ADMIN) so every
 * viewer here already cleared the admin gate; write controls check
 * `user-admin`, matching ClientResource's own POST/PUT/deactivate/reactivate
 * RBAC (A10, row :1420). There is no DELETE -- id 0 is the system client
 * (isSystemClient, derived server-side) and never offers deactivate,
 * mirroring the backend's own refusal.
 */
export function AdminClientsPage() {
  const { hasPermission } = usePermissions();
  const canWrite = hasPermission('user-admin');

  const { data: clients = [] } = useClients();
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [search, setSearch] = useState('');
  const [creating, setCreating] = useState(false);

  const selected = selectedId != null ? (clients.find((c) => c.id === selectedId) ?? null) : null;
  const filtered = clients.filter((c) =>
    `${c.name} ${c.number}`.toLowerCase().includes(search.trim().toLowerCase()),
  );

  const activeCount = clients.filter((c) => c.state === 'ACTIVE').length;
  const inactiveCount = clients.filter((c) => c.state === 'INACTIVE').length;

  const tiles = [
    { label: 'Total clients', value: String(clients.length) },
    { label: 'Active', value: String(activeCount), lime: true },
    { label: 'Inactive', value: String(inactiveCount) },
  ];

  return (
    <div data-testid="admin-clients-page">
      <div className="mb-5 flex items-start justify-between">
        <div>
          <h1 className="font-display text-2xl font-bold tracking-[-0.02em] text-foreground">
            Clients
          </h1>
          <p className="mt-1 text-[13px] text-muted-foreground">
            Goods-owner administration · every 3PL customer and ops entity served by this
            instance.
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
            + New client
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

      {canWrite && <ConsistencySection />}

      <MasterDetailLayout
        list={
          <MasterList
            searchValue={search}
            onSearchChange={setSearch}
            searchPlaceholder="Search clients…"
          >
            {filtered.map((c) => (
              <MasterListRow
                key={c.id}
                tone={c.state === 'ACTIVE' ? 'lime' : 'grey'}
                active={c.id === selected?.id && !creating}
                onClick={() => {
                  setSelectedId(c.id);
                  setCreating(false);
                }}
              >
                <div className="flex items-center gap-3">
                  <div className="flex size-[34px] flex-none items-center justify-center rounded-[10px] border border-border text-muted-foreground/70">
                    <Briefcase className="size-[17px]" />
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="text-[14px] font-semibold text-foreground">{c.name}</span>
                      {c.isSystemClient && <StatusPill label="System" tone="violet" />}
                    </div>
                    <div className="numeric mt-0.5 truncate text-[11px] text-muted-foreground/70">
                      {c.number}
                    </div>
                  </div>
                  <StatusPill
                    label={c.state === 'ACTIVE' ? 'Active' : 'Inactive'}
                    tone={c.state === 'ACTIVE' ? 'lime' : 'grey'}
                  />
                </div>
              </MasterListRow>
            ))}
          </MasterList>
        }
        detail={
          creating ? (
            <CreateForm onClose={() => setCreating(false)} />
          ) : selected ? (
            <DetailPanel key={selected.id} client={selected} canWrite={canWrite} />
          ) : (
            <div className="rounded-2xl border border-border bg-card p-8 text-center text-[13px] text-muted-foreground">
              {clients.length === 0
                ? 'No clients yet. Create one to start assigning stock ownership.'
                : 'Select a client to view its details.'}
            </div>
          )
        }
      />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Consistency check
// ---------------------------------------------------------------------------

function ConsistencySection() {
  const { data, refetch, isFetching } = useConsistencyCheck();

  return (
    <div className="mb-5">
      <SectionCard
        title="Consistency"
        action={
          <button
            type="button"
            onClick={() => void refetch()}
            disabled={isFetching}
            className="h-8 rounded-lg border border-border px-3 text-[12px] text-foreground disabled:opacity-60"
          >
            Run check
          </button>
        }
      >
        {!data ? (
          <p className="text-[12.5px] text-muted-foreground">
            Scans every <code className="numeric">client_id</code>-carrying table for references
            to a client id that no longer exists. Advisory only — no foreign keys enforce this.
          </p>
        ) : data.danglingClientIds.length === 0 ? (
          <p className="text-[12.5px] text-muted-foreground">
            No dangling references — checked {new Date(data.checkedAt).toLocaleString()}.
          </p>
        ) : (
          <div>
            <p className="mb-2 text-[12.5px] text-warning-foreground">
              References to client ids that no longer exist:
            </p>
            <code className="numeric block rounded-lg border border-border bg-background p-3 text-[12px] text-foreground">
              {data.danglingClientIds.join(', ')}
            </code>
          </div>
        )}
      </SectionCard>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Create form
// ---------------------------------------------------------------------------

function CreateForm({ onClose }: { onClose: () => void }) {
  const create = useCreateClient();
  const [name, setName] = useState('');
  const [number, setNumber] = useState('');
  const [code, setCode] = useState('');
  const [email, setEmail] = useState('');
  const [phone, setPhone] = useState('');
  const [fax, setFax] = useState('');

  async function submit() {
    if (!name || !number) {
      toast.error('Name and number are required');
      return;
    }
    const body: CreateClientRequest = {
      name,
      number,
      code: code || undefined,
      email: email || undefined,
      phone: phone || undefined,
      fax: fax || undefined,
    };
    try {
      await create.mutateAsync(body);
      toast('Client created');
      onClose();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Failed to create client');
    }
  }

  return (
    <section className="rounded-2xl border border-border bg-card p-5">
      <h2 className="mb-4 text-base font-semibold text-foreground">New client</h2>
      <Field label="Name" htmlFor="client-create-name">
        <input
          id="client-create-name"
          className={inputCls}
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="Acme Corp"
        />
      </Field>
      <Field label="Number" htmlFor="client-create-number">
        <input
          id="client-create-number"
          className={inputCls}
          value={number}
          onChange={(e) => setNumber(e.target.value)}
          placeholder="CL-100"
        />
      </Field>
      <Field label="Code" htmlFor="client-create-code">
        <input
          id="client-create-code"
          className={inputCls}
          value={code}
          onChange={(e) => setCode(e.target.value)}
        />
      </Field>
      <Field label="Email" htmlFor="client-create-email">
        <input
          id="client-create-email"
          className={inputCls}
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
      </Field>
      <Field label="Phone" htmlFor="client-create-phone">
        <input
          id="client-create-phone"
          className={inputCls}
          value={phone}
          onChange={(e) => setPhone(e.target.value)}
        />
      </Field>
      <Field label="Fax" htmlFor="client-create-fax">
        <input
          id="client-create-fax"
          className={inputCls}
          value={fax}
          onChange={(e) => setFax(e.target.value)}
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

function DetailPanel({ client, canWrite }: { client: ClientResponse; canWrite: boolean }) {
  const update = useUpdateClient(client.id);
  const setActive = useSetClientActive(client.id);
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(client.name);
  const [code, setCode] = useState(client.code);
  const [email, setEmail] = useState(client.email);
  const [phone, setPhone] = useState(client.phone);
  const [fax, setFax] = useState(client.fax);

  async function save() {
    try {
      await update.mutateAsync({
        name,
        code: code || undefined,
        email: email || undefined,
        phone: phone || undefined,
        fax: fax || undefined,
      });
      toast('Client updated');
      setEditing(false);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Failed to update client');
    }
  }

  const isActive = client.state === 'ACTIVE';

  return (
    <section className="rounded-2xl border border-border bg-card p-5">
      <div className="mb-4 flex items-start justify-between">
        <div>
          <div className="flex items-center gap-2">
            <h2 className="text-base font-semibold text-foreground">{client.name}</h2>
            {client.isSystemClient && <StatusPill label="System" tone="violet" />}
          </div>
          <p className="numeric mt-1 text-[11px] text-muted-foreground/70">{client.number}</p>
        </div>
        {/* Backend refuses to deactivate the system client — don't offer it. */}
        {canWrite && !client.isSystemClient && (
          <button
            type="button"
            onClick={() =>
              setActive.mutate(!isActive, {
                onSuccess: () => toast(isActive ? 'Client deactivated' : 'Client reactivated'),
              })
            }
            disabled={setActive.isPending}
            className="flex h-9 items-center rounded-lg border border-border px-3 text-[12px] text-foreground disabled:opacity-60"
          >
            {isActive ? 'Deactivate' : 'Reactivate'}
          </button>
        )}
      </div>

      {editing && canWrite ? (
        <div>
          <Field label="Name" htmlFor="client-edit-name">
            <input
              id="client-edit-name"
              className={inputCls}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </Field>
          <Field label="Number">
            <input
              className={cn(inputCls, 'opacity-60')}
              value={client.number}
              disabled
              aria-label="Number (immutable)"
            />
          </Field>
          <Field label="Code" htmlFor="client-edit-code">
            <input
              id="client-edit-code"
              className={inputCls}
              value={code}
              onChange={(e) => setCode(e.target.value)}
            />
          </Field>
          <Field label="Email" htmlFor="client-edit-email">
            <input
              id="client-edit-email"
              className={inputCls}
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          </Field>
          <Field label="Phone" htmlFor="client-edit-phone">
            <input
              id="client-edit-phone"
              className={inputCls}
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
            />
          </Field>
          <Field label="Fax" htmlFor="client-edit-fax">
            <input
              id="client-edit-fax"
              className={inputCls}
              value={fax}
              onChange={(e) => setFax(e.target.value)}
            />
          </Field>
          <div className="mt-4 flex gap-2.5">
            <button
              type="button"
              onClick={() => void save()}
              disabled={update.isPending}
              className="font-display h-10 flex-1 rounded-xl bg-primary text-[13px] font-bold text-primary-foreground disabled:opacity-60"
            >
              Save
            </button>
            <button
              type="button"
              onClick={() => setEditing(false)}
              className="font-display h-10 rounded-xl border border-border px-4 text-[12px] text-muted-foreground"
            >
              Cancel
            </button>
          </div>
        </div>
      ) : (
        <>
          <AttributeGrid
            items={[
              { label: 'Number', value: client.number },
              { label: 'Code', value: client.code || null },
              { label: 'Email', value: client.email || null },
              { label: 'Phone', value: client.phone || null },
              { label: 'Fax', value: client.fax || null },
              { label: 'State', value: isActive ? 'Active' : 'Inactive' },
            ]}
          />
          {canWrite && (
            <button
              type="button"
              onClick={() => setEditing(true)}
              className="font-display mt-4 h-10 rounded-xl border border-border px-4 text-[12px] text-foreground"
            >
              Edit
            </button>
          )}
        </>
      )}
    </section>
  );
}

// ---------------------------------------------------------------------------
// Micro helpers
// ---------------------------------------------------------------------------

const inputCls =
  'h-10 w-full rounded-xl border border-border bg-background px-3 text-[13px] text-foreground';

function Field({
  label,
  htmlFor,
  children,
}: {
  label: string;
  htmlFor?: string;
  children: ReactNode;
}) {
  return (
    <div className="mb-3">
      <label
        htmlFor={htmlFor}
        className="numeric mb-1.5 block text-[10px] font-semibold uppercase tracking-[0.12em] text-muted-foreground/70"
      >
        {label}
      </label>
      {children}
    </div>
  );
}
