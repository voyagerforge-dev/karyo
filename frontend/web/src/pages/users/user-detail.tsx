import { useState } from 'react';
import { Pencil, UserCheck, UserX } from 'lucide-react';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { SectionCard } from '@/components/control/section-card';
import { AttributeGrid } from '@/components/control/attribute-grid';
import { StatusPill } from '@/components/control/status-pill';
import type { EntityTone } from '@/components/master-detail/tones';
import { useUser, useDeactivateUser, useReactivateUser } from './use-users';
import { UserForm } from './user-form';

interface UserDetailProps {
  userId: string;
}

/** Map role names to a Control tone (ported from user-columns.tsx's roleBadgeVariant). */
function roleTone(role: string): EntityTone {
  switch (role) {
    case 'ADMIN':
      return 'red';
    case 'MANAGER':
      return 'blue';
    default:
      return 'grey';
  }
}

function displayName(user: { firstName: string | null; lastName: string | null; username: string }): string {
  if (user.firstName && user.lastName) return `${user.firstName} ${user.lastName}`;
  return user.username;
}

/**
 * User detail-as-workspace (Task 4 of the Control migration -- the last
 * legacy screen). Header + AttributeGrid + roles, an inline edit form
 * (ported from the old drawer's UserForm, behavior verbatim) and the
 * deactivate/reactivate confirm dialog (ported wholesale from the old
 * users-page.tsx). Mounted with `key={selectedId}` by the parent page so
 * `editOpen` resets on every selection switch.
 */
export function UserDetail({ userId }: UserDetailProps) {
  const { data: user, isLoading } = useUser(userId);
  const deactivateMutation = useDeactivateUser();
  const reactivateMutation = useReactivateUser();

  const [editOpen, setEditOpen] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);

  if (isLoading || !user) {
    return (
      <div className="space-y-4 rounded-2xl border border-border bg-card p-6">
        <Skeleton className="h-6 w-1/2" />
        <Skeleton className="h-24 w-full" />
      </div>
    );
  }

  const isMutating = deactivateMutation.isPending || reactivateMutation.isPending;

  function handleConfirmToggle() {
    const mutation = user!.enabled ? deactivateMutation : reactivateMutation;
    mutation.mutate(user!.id, { onSuccess: () => setConfirmOpen(false) });
  }

  return (
    <div className="space-y-4" data-testid="user-detail">
      <SectionCard>
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0">
            <div className="flex items-center gap-3">
              <h1 className="font-display text-[20px] font-bold text-foreground">
                {displayName(user)}
              </h1>
              <StatusPill
                label={user.enabled ? 'Active' : 'Deactivated'}
                tone={user.enabled ? 'lime' : 'grey'}
              />
            </div>
            <p className="mt-1 text-[13px] text-foreground/70">{user.username}</p>
          </div>
          <Button
            variant="outline"
            onClick={() => setEditOpen((v) => !v)}
            data-testid="user-edit-toggle"
          >
            <Pencil className="size-4" />
            {editOpen ? 'Close edit' : 'Edit'}
          </Button>
        </div>

        <div className="mt-5">
          <AttributeGrid
            items={[
              { label: 'Username', value: user.username },
              { label: 'Email', value: user.email },
              { label: 'Tenant', value: user.tenantCode },
              { label: 'Warehouse', value: user.warehouseId },
              {
                label: 'Created',
                value: user.createdTimestamp
                  ? new Date(user.createdTimestamp).toLocaleDateString()
                  : null,
              },
            ]}
          />
        </div>

        <div className="mt-5 border-t border-border pt-4">
          <div className="mb-2 text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground/70">
            Roles
          </div>
          <div className="flex flex-wrap gap-1.5">
            {user.roles.length === 0 ? (
              <span className="text-sm text-muted-foreground">No roles</span>
            ) : (
              user.roles.map((role) => (
                <StatusPill key={role} label={role} tone={roleTone(role)} />
              ))
            )}
          </div>
        </div>

        <div className="mt-5 flex flex-wrap items-center gap-2 border-t border-border pt-4">
          {user.enabled ? (
            <Button
              variant="outline"
              className="text-destructive"
              onClick={() => setConfirmOpen(true)}
              disabled={isMutating}
              data-testid="user-deactivate-button"
            >
              <UserX className="size-4" />
              Deactivate
            </Button>
          ) : (
            <Button
              onClick={() => setConfirmOpen(true)}
              disabled={isMutating}
              data-testid="user-reactivate-button"
            >
              <UserCheck className="size-4" />
              Reactivate
            </Button>
          )}
        </div>
      </SectionCard>

      {editOpen && (
        <SectionCard title="Edit user">
          <UserForm user={user} onClose={() => setEditOpen(false)} />
        </SectionCard>
      )}

      <AlertDialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {user.enabled ? 'Deactivate User?' : 'Reactivate User?'}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {user.enabled
                ? `This will deactivate "${user.username}". They will not be able to log in.`
                : `This will reactivate "${user.username}". They will be able to log in again.`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={handleConfirmToggle}>
              {user.enabled ? 'Deactivate' : 'Reactivate'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
