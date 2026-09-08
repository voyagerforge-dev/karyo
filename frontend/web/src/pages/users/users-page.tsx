import { useDeferredValue, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import { Plus, Users } from 'lucide-react';
import {
  MasterDetailLayout,
  MasterList,
  MasterListRow,
  FilterChips,
  DetailEmptyState,
} from '@/components/master-detail/master-detail';
import type { FilterChipOption } from '@/components/master-detail/master-detail';
import { SectionCard } from '@/components/control/section-card';
import { StatusPill } from '@/components/control/status-pill';
import { Skeleton } from '@/components/ui/skeleton';
import { usePermissions } from '@/hooks/use-permissions';
import { useUsers } from './use-users';
import { UserDetail } from './user-detail';
import { UserForm } from './user-form';
import type { UserResponse } from '@/types/user';

type UserFilter = 'all' | 'active' | 'deactivated';

const FILTERS: ReadonlyArray<FilterChipOption<UserFilter>> = [
  { value: 'all', label: 'All' },
  { value: 'active', label: 'Active' },
  { value: 'deactivated', label: 'Deactivated' },
];

function displayName(user: Pick<UserResponse, 'firstName' | 'lastName' | 'username'>): string {
  if (user.firstName && user.lastName) return `${user.firstName} ${user.lastName}`;
  return user.username;
}

/** One row in the master list (ported from the retired user-columns.tsx). */
function UserRow({
  user,
  active,
  onClick,
}: {
  user: UserResponse;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <MasterListRow
      tone={user.enabled ? 'lime' : 'grey'}
      active={active}
      onClick={onClick}
      testId={`user-row-${user.id}`}
    >
      <div className="flex items-center justify-between gap-2">
        <span className="truncate text-[13px] font-semibold text-foreground">
          {displayName(user)}
        </span>
        <StatusPill label={user.enabled ? 'Active' : 'Deactivated'} tone={user.enabled ? 'lime' : 'grey'} />
      </div>
      <div className="mt-1.5 truncate text-[12.5px] text-foreground/70">
        {user.email ?? user.username}
      </div>
      {user.roles.length > 0 && (
        <div className="mt-1.5 truncate text-[11px] font-medium text-muted-foreground">
          {user.roles.join(', ')}
        </div>
      )}
    </MasterListRow>
  );
}

/**
 * Users master-detail list (Task 4 of the Control migration -- the last
 * legacy EntityDrawer/DataTable consumer). Rebuilt onto the shared
 * master-detail kit, mirroring ReceivingPage/CycleCountPage (the freshest
 * precedents). Row click SELECTS into the detail pane; the pane owns the
 * edit affordance and the deactivate/reactivate action.
 */
export function UsersPage() {
  const { hasPermission } = usePermissions();
  const isAdmin = hasPermission('user-admin');

  // ⌘K palette deep link: ?create=1 opens the create form in the detail slot
  // (mirrors ReceivingPage/OrdersPage — the freshest precedents).
  const [searchParams, setSearchParams] = useSearchParams();
  const [search, setSearch] = useState('');
  const deferredSearch = useDeferredValue(search);
  const [filter, setFilter] = useState<UserFilter>('all');
  const [page, setPage] = useState(0);
  const [selectedId, setSelectedId] = useState<string | undefined>();
  const [formOpen, setFormOpen] = useState(() => searchParams.get('create') === '1');

  useEffect(() => {
    const create = searchParams.get('create');
    if (create === null) return;
    // Apply the deep-link param before stripping it — a command-palette
    // navigation to /users?create=1 while this page is already mounted only
    // fires this effect (the useState initializer above never re-runs), so
    // applying here is the only place it can land (same as TasksPage).
    if (create === '1') setFormOpen(true);
    setSearchParams({}, { replace: true });
  }, [searchParams, setSearchParams]);

  const searchQuery = deferredSearch.trim() || undefined;
  const enabled = filter === 'all' ? undefined : filter === 'active';
  const { data, isLoading } = useUsers({
    page,
    size: 100,
    search: searchQuery,
    enabled,
  });
  const users = data?.content ?? [];
  const totalPages = data?.page.totalPages ?? 0;

  useEffect(() => {
    if (data == null) return;
    const lastPage = Math.max(0, totalPages - 1);
    if (page > lastPage) setPage(lastPage);
  }, [data, page, totalPages]);

  function handleOpenCreate() {
    setFormOpen(true);
    setSelectedId(undefined);
  }

  // Permission-gated: show unauthorized if not admin
  if (!isAdmin) {
    return (
      <div className="flex flex-col items-center justify-center py-12 space-y-2">
        <h1 className="text-2xl font-semibold">Unauthorized</h1>
        <p className="text-muted-foreground">
          You need the user-admin permission to access this page.
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-4" data-testid="users-page">
      <div className="flex items-center justify-between">
        <h1 className="font-display text-2xl font-bold tracking-[-0.02em]">Users</h1>
        <button
          type="button"
          onClick={handleOpenCreate}
          className="flex h-9 items-center gap-1.5 rounded-[9px] bg-primary px-3.5 text-[13px] font-bold text-primary-foreground"
          data-testid="user-create-button"
        >
          <Plus className="size-4" />
          Create User
        </button>
      </div>

      <MasterDetailLayout
        list={
          <MasterList
            searchValue={search}
            onSearchChange={(value) => {
              setSearch(value);
              setPage(0);
            }}
            searchPlaceholder="Search username, name or email…"
            chips={
              <FilterChips
                options={FILTERS}
                value={filter}
                onChange={(value) => {
                  setFilter(value);
                  setPage(0);
                }}
              />
            }
            footer={
              data != null && (
                <div
                  className="flex flex-none items-center justify-between border-t border-border pt-2 text-xs text-muted-foreground"
                  data-testid="users-pagination"
                >
                  <span>{data.page.totalElements} users</span>
                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      className="rounded-md border border-border px-2 py-1 disabled:opacity-40"
                      disabled={page === 0}
                      onClick={() => setPage((current) => Math.max(0, current - 1))}
                    >
                      Previous
                    </button>
                    <span className="numeric">
                      {totalPages === 0 ? 0 : page + 1} / {totalPages}
                    </span>
                    <button
                      type="button"
                      className="rounded-md border border-border px-2 py-1 disabled:opacity-40"
                      disabled={page + 1 >= totalPages}
                      onClick={() => setPage((current) => current + 1)}
                    >
                      Next
                    </button>
                  </div>
                </div>
              )
            }
          >
            {isLoading ? (
              <>
                <Skeleton className="h-[76px] w-full rounded-xl" />
                <Skeleton className="h-[76px] w-full rounded-xl" />
                <Skeleton className="h-[76px] w-full rounded-xl" />
              </>
            ) : users.length === 0 ? (
              <p className="px-1 py-6 text-center text-sm text-muted-foreground">
                No users match.
              </p>
            ) : (
              users.map((u) => (
                <UserRow
                  key={u.id}
                  user={u}
                  active={u.id === selectedId && !formOpen}
                  onClick={() => {
                    setSelectedId(u.id);
                    setFormOpen(false);
                  }}
                />
              ))
            )}
          </MasterList>
        }
        detail={
          formOpen ? (
            <SectionCard title="New user">
              <UserForm onClose={() => setFormOpen(false)} />
            </SectionCard>
          ) : selectedId != null ? (
            <UserDetail key={selectedId} userId={selectedId} />
          ) : (
            <DetailEmptyState
              icon={<Users className="size-8 opacity-40" />}
              message="Select a user"
            />
          )
        }
      />
    </div>
  );
}
