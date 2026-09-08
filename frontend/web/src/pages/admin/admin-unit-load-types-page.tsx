import { useState, type ReactNode } from 'react';
import { Package } from 'lucide-react';
import { toast } from 'sonner';
import { cn } from '@/lib/utils';
import {
  MasterDetailLayout,
  MasterList,
  MasterListRow,
} from '@/components/master-detail/master-detail';
import { StatusPill } from '@/components/control/status-pill';
import { AttributeGrid } from '@/components/control/attribute-grid';
import { Switch } from '@/components/ui/switch';
import { usePermissions } from '@/hooks/use-permissions';
import {
  useUnitLoadTypes,
  useCreateUnitLoadType,
  useUpdateUnitLoadType,
} from '@/features/unit-load-types/use-unit-load-types';
import type {
  CreateUnitLoadTypeRequest,
  UnitLoadTypeResponse,
  UpdateUnitLoadTypeRequest,
} from '@/types/inventory';

/**
 * Unit load type administration, row 16c. Reads are inventory-read server-side, but this
 * page lives under /admin (AdminGuard, user-admin) so every viewer here already cleared the
 * admin gate; write controls check `inventory-write`, matching the backend's own
 * POST/PUT/DELETE RBAC on UnitLoadTypeResource (A10, row :1420) -- the route guard stays
 * user-admin, but each page's canWrite mirrors what its own backend actually enforces.
 *
 * PUT /api/v1/unit-load-types/{id} is a full-representation update: any field the request
 * omits is reset to its default, not left alone. The edit form below always submits every
 * field from its own state (seeded from the selected type on open), never a partial patch;
 * see `UpdateUnitLoadTypeRequest` in types/inventory.ts, which makes every field required
 * for exactly this reason.
 */
export function AdminUnitLoadTypesPage() {
  const { hasPermission } = usePermissions();
  const canWrite = hasPermission('inventory-write');

  const { data: types = [] } = useUnitLoadTypes();
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [search, setSearch] = useState('');
  const [creating, setCreating] = useState(false);

  const selected = selectedId != null ? (types.find((t) => t.id === selectedId) ?? null) : null;
  const filtered = types.filter((t) => t.name.toLowerCase().includes(search.trim().toLowerCase()));

  const reusableCount = types.filter((t) => t.manageEmpties).length;

  const tiles = [
    { label: 'Total types', value: String(types.length) },
    { label: 'Manage empties', value: String(reusableCount), lime: true },
    { label: 'Aggregate stocks', value: String(types.filter((t) => t.aggregateStocks).length) },
  ];

  return (
    <div data-testid="admin-unit-load-types-page">
      <div className="mb-5 flex items-start justify-between">
        <div>
          <h1 className="font-display text-2xl font-bold tracking-[-0.02em] text-foreground">
            Unit load types
          </h1>
          <p className="mt-1 text-[13px] text-muted-foreground">
            Container catalog · pallets, totes and cartons available for putaway and picking.
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
            + New type
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
            searchPlaceholder="Search unit load types…"
          >
            {filtered.map((t) => (
              <MasterListRow
                key={t.id}
                tone={t.manageEmpties ? 'lime' : 'grey'}
                active={t.id === selected?.id && !creating}
                onClick={() => {
                  setSelectedId(t.id);
                  setCreating(false);
                }}
              >
                <div className="flex items-center gap-3">
                  <div className="flex size-[34px] flex-none items-center justify-center rounded-[10px] border border-border text-muted-foreground/70">
                    <Package className="size-[17px]" />
                  </div>
                  <div className="min-w-0 flex-1">
                    <span className="text-[14px] font-semibold text-foreground">{t.name}</span>
                    {t.usages && (
                      <div className="numeric mt-0.5 truncate text-[11px] text-muted-foreground/70">
                        {t.usages}
                      </div>
                    )}
                  </div>
                  <StatusPill
                    label={t.manageEmpties ? 'Manage empties: On' : 'Manage empties: Off'}
                    tone={t.manageEmpties ? 'lime' : 'grey'}
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
            <DetailPanel key={selected.id} type={selected} canWrite={canWrite} />
          ) : (
            <div className="rounded-2xl border border-border bg-card p-8 text-center text-[13px] text-muted-foreground">
              {types.length === 0
                ? 'No unit load types yet. Create one to start assigning containers.'
                : 'Select a unit load type to view its details.'}
            </div>
          )
        }
      />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Create form
// ---------------------------------------------------------------------------

function CreateForm({ onClose }: { onClose: () => void }) {
  const create = useCreateUnitLoadType();
  const [name, setName] = useState('');
  const [height, setHeight] = useState('');
  const [width, setWidth] = useState('');
  const [depth, setDepth] = useState('');
  const [liftingCapacity, setLiftingCapacity] = useState('');
  const [weight, setWeight] = useState('');
  const [usages, setUsages] = useState('');
  const [aggregateStocks, setAggregateStocks] = useState(false);
  const [manageEmpties, setManageEmpties] = useState(false);

  async function submit() {
    if (!name) {
      toast.error('Name is required');
      return;
    }
    const body: CreateUnitLoadTypeRequest = {
      name,
      usages: usages || undefined,
      aggregateStocks,
      height: height === '' ? undefined : Number(height),
      width: width === '' ? undefined : Number(width),
      depth: depth === '' ? undefined : Number(depth),
      liftingCapacity: liftingCapacity === '' ? undefined : Number(liftingCapacity),
      weight: weight === '' ? undefined : Number(weight),
      manageEmpties,
    };
    try {
      await create.mutateAsync(body);
      toast('Unit load type created');
      onClose();
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Failed to create unit load type');
    }
  }

  return (
    <section className="rounded-2xl border border-border bg-card p-5">
      <h2 className="mb-4 text-base font-semibold text-foreground">New unit load type</h2>
      <Field label="Name" htmlFor="ult-create-name">
        <input
          id="ult-create-name"
          className={inputCls}
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="Euro pallet"
        />
      </Field>
      <Field label="Height" htmlFor="ult-create-height">
        <input
          id="ult-create-height"
          type="number"
          className={inputCls}
          value={height}
          onChange={(e) => setHeight(e.target.value)}
        />
      </Field>
      <Field label="Width" htmlFor="ult-create-width">
        <input
          id="ult-create-width"
          type="number"
          className={inputCls}
          value={width}
          onChange={(e) => setWidth(e.target.value)}
        />
      </Field>
      <Field label="Depth" htmlFor="ult-create-depth">
        <input
          id="ult-create-depth"
          type="number"
          className={inputCls}
          value={depth}
          onChange={(e) => setDepth(e.target.value)}
        />
      </Field>
      <Field label="Lifting capacity" htmlFor="ult-create-lifting-capacity">
        <input
          id="ult-create-lifting-capacity"
          type="number"
          className={inputCls}
          value={liftingCapacity}
          onChange={(e) => setLiftingCapacity(e.target.value)}
        />
      </Field>
      <Field label="Weight (empty container)" htmlFor="ult-create-weight">
        <input
          id="ult-create-weight"
          type="number"
          className={inputCls}
          value={weight}
          onChange={(e) => setWeight(e.target.value)}
          placeholder="Tare weight, not a capacity"
        />
      </Field>
      <Field label="Usages" htmlFor="ult-create-usages">
        <input
          id="ult-create-usages"
          className={inputCls}
          value={usages}
          onChange={(e) => setUsages(e.target.value)}
          placeholder="PALLET, TOTE"
        />
      </Field>
      <BooleanField label="Aggregate stocks" checked={aggregateStocks} onChange={setAggregateStocks} />
      <BooleanField label="Manage empties" checked={manageEmpties} onChange={setManageEmpties} />
      <ManageEmptiesHelp />
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

function DetailPanel({ type, canWrite }: { type: UnitLoadTypeResponse; canWrite: boolean }) {
  const update = useUpdateUnitLoadType(type.id);
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(type.name);
  const [height, setHeight] = useState(type.height == null ? '' : String(type.height));
  const [width, setWidth] = useState(type.width == null ? '' : String(type.width));
  const [depth, setDepth] = useState(type.depth == null ? '' : String(type.depth));
  const [liftingCapacity, setLiftingCapacity] = useState(
    type.liftingCapacity == null ? '' : String(type.liftingCapacity),
  );
  const [weight, setWeight] = useState(type.weight == null ? '' : String(type.weight));
  const [usages, setUsages] = useState(type.usages ?? '');
  const [aggregateStocks, setAggregateStocks] = useState(type.aggregateStocks);
  const [manageEmpties, setManageEmpties] = useState(type.manageEmpties);

  async function save() {
    // Full-representation body: send every field from the form's own state,
    // not just whatever the operator touched. Leaving a field out here would
    // silently reset it to the backend default on save.
    const body: UpdateUnitLoadTypeRequest = {
      name,
      usages: usages === '' ? null : usages,
      aggregateStocks,
      height: height === '' ? null : Number(height),
      width: width === '' ? null : Number(width),
      depth: depth === '' ? null : Number(depth),
      liftingCapacity: liftingCapacity === '' ? null : Number(liftingCapacity),
      weight: weight === '' ? null : Number(weight),
      manageEmpties,
    };
    try {
      await update.mutateAsync(body);
      toast('Unit load type updated');
      setEditing(false);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'Failed to update unit load type');
    }
  }

  return (
    <section className="rounded-2xl border border-border bg-card p-5">
      <div className="mb-4 flex items-start justify-between">
        <div>
          <h2 className="text-base font-semibold text-foreground">{type.name}</h2>
          {type.usages && (
            <p className="numeric mt-1 text-[11px] text-muted-foreground/70">{type.usages}</p>
          )}
        </div>
        <StatusPill
          label={type.manageEmpties ? 'Manage empties: On' : 'Manage empties: Off'}
          tone={type.manageEmpties ? 'lime' : 'grey'}
        />
      </div>

      {editing && canWrite ? (
        <div>
          <Field label="Name" htmlFor="ult-edit-name">
            <input
              id="ult-edit-name"
              className={inputCls}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </Field>
          <Field label="Height" htmlFor="ult-edit-height">
            <input
              id="ult-edit-height"
              type="number"
              className={inputCls}
              value={height}
              onChange={(e) => setHeight(e.target.value)}
            />
          </Field>
          <Field label="Width" htmlFor="ult-edit-width">
            <input
              id="ult-edit-width"
              type="number"
              className={inputCls}
              value={width}
              onChange={(e) => setWidth(e.target.value)}
            />
          </Field>
          <Field label="Depth" htmlFor="ult-edit-depth">
            <input
              id="ult-edit-depth"
              type="number"
              className={inputCls}
              value={depth}
              onChange={(e) => setDepth(e.target.value)}
            />
          </Field>
          <Field label="Lifting capacity" htmlFor="ult-edit-lifting-capacity">
            <input
              id="ult-edit-lifting-capacity"
              type="number"
              className={inputCls}
              value={liftingCapacity}
              onChange={(e) => setLiftingCapacity(e.target.value)}
            />
          </Field>
          <Field label="Weight (empty container)" htmlFor="ult-edit-weight">
            <input
              id="ult-edit-weight"
              type="number"
              className={inputCls}
              value={weight}
              onChange={(e) => setWeight(e.target.value)}
            />
          </Field>
          <Field label="Usages" htmlFor="ult-edit-usages">
            <input
              id="ult-edit-usages"
              className={inputCls}
              value={usages}
              onChange={(e) => setUsages(e.target.value)}
            />
          </Field>
          <BooleanField
            label="Aggregate stocks"
            checked={aggregateStocks}
            onChange={setAggregateStocks}
          />
          <BooleanField label="Manage empties" checked={manageEmpties} onChange={setManageEmpties} />
          <ManageEmptiesHelp />
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
              { label: 'Height', value: type.height == null ? null : String(type.height) },
              { label: 'Width', value: type.width == null ? null : String(type.width) },
              { label: 'Depth', value: type.depth == null ? null : String(type.depth) },
              {
                label: 'Lifting capacity',
                value: type.liftingCapacity == null ? null : String(type.liftingCapacity),
              },
              {
                label: 'Weight (empty container)',
                value: type.weight == null ? null : String(type.weight),
              },
              { label: 'Usages', value: type.usages },
              { label: 'Aggregate stocks', value: type.aggregateStocks ? 'Yes' : 'No' },
            ]}
          />
          <ManageEmptiesHelp className="mt-4" />
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

function BooleanField({
  label,
  checked,
  onChange,
}: {
  label: string;
  checked: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <div className="mb-3 flex items-center justify-between">
      <span className="text-[13px] text-foreground">{label}</span>
      <Switch checked={checked} onCheckedChange={onChange} aria-label={label} />
    </div>
  );
}

/** Row 16: plain-language explanation of what the manageEmpties toggle does. */
function ManageEmptiesHelp({ className }: { className?: string }) {
  return (
    <p className={cn('text-[12px] text-muted-foreground', className)}>
      When on, an emptied container of this type is kept for reuse, such as a tote or pallet
      going back into circulation, instead of being retired automatically once it goes empty.
    </p>
  );
}
