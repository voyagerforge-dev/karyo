import { useState } from 'react';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Checkbox } from '@/components/ui/checkbox';
import { Button } from '@/components/ui/button';
import {
  useCreateUser,
  useUpdateUser,
  useAssignRole,
  useRevokeRole,
} from './use-users';
import { useClients } from '@/features/clients/use-clients';
import { AVAILABLE_ROLES } from '@/types/user';
import type { PrincipalKind, UserResponse } from '@/types/user';

interface UserFormProps {
  /** Existing user for edit mode; omit for create mode */
  user?: UserResponse;
  onClose: () => void;
}

interface FormState {
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  password: string;
  forcePasswordChange: boolean;
  roles: string[];
  clientId: string;
  principalKind: '' | PrincipalKind;
  warehouseId: string;
}

interface FormErrors {
  username?: string;
  email?: string;
  firstName?: string;
  lastName?: string;
  password?: string;
  clientId?: string;
  principalKind?: string;
}

export function UserForm({ user, onClose }: UserFormProps) {
  const isEdit = !!user;
  const createMutation = useCreateUser();
  const updateMutation = useUpdateUser(user?.id ?? '');
  const assignRoleMutation = useAssignRole(user?.id ?? '');
  const revokeRoleMutation = useRevokeRole(user?.id ?? '');
  const { data: clients = [], isLoading: clientsLoading } = useClients();

  const [form, setForm] = useState<FormState>({
    username: user?.username ?? '',
    email: user?.email ?? '',
    firstName: user?.firstName ?? '',
    lastName: user?.lastName ?? '',
    password: '',
    forcePasswordChange: false,
    roles: user?.roles ?? [],
    clientId: '',
    principalKind: '',
    warehouseId: user?.warehouseId ?? '',
  });

  const [errors, setErrors] = useState<FormErrors>({});

  function validate(): boolean {
    const newErrors: FormErrors = {};
    if (!isEdit && !form.username.trim()) newErrors.username = 'Username is required';
    if (!form.email.trim()) newErrors.email = 'Email is required';
    if (!isEdit && !form.firstName.trim()) newErrors.firstName = 'First name is required';
    if (!isEdit && !form.lastName.trim()) newErrors.lastName = 'Last name is required';
    if (!isEdit && !form.password) newErrors.password = 'Password is required';
    if (!isEdit && form.password && form.password.length < 8) {
      newErrors.password = 'Password must be at least 8 characters';
    }
    if (!isEdit && !form.clientId) newErrors.clientId = 'Goods owner is required';
    if (!isEdit && !form.principalKind) newErrors.principalKind = 'Tenant authority is required';
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  }

  function handleSubmit() {
    if (!validate()) return;

    if (isEdit) {
      updateMutation.mutate(
        {
          email: form.email.trim(),
          firstName: form.firstName.trim(),
          lastName: form.lastName.trim(),
          warehouseId: form.warehouseId.trim() || undefined,
        },
        { onSuccess: () => onClose() },
      );
    } else {
      createMutation.mutate(
        {
          username: form.username.trim(),
          email: form.email.trim(),
          firstName: form.firstName.trim(),
          lastName: form.lastName.trim(),
          password: form.password,
          roles: form.roles,
          clientId: Number(form.clientId),
          principalKind: form.principalKind as PrincipalKind,
          warehouseId: form.warehouseId.trim() || undefined,
          forcePasswordChange: form.forcePasswordChange,
        },
        { onSuccess: () => onClose() },
      );
    }
  }

  function handleRoleToggle(role: string, checked: boolean) {
    if (isEdit) {
      // In edit mode, apply role changes immediately via API
      if (checked) {
        assignRoleMutation.mutate(role);
      } else {
        revokeRoleMutation.mutate(role);
      }
    }
    // Update local state regardless
    setForm((prev) => ({
      ...prev,
      roles: checked
        ? [...prev.roles, role]
        : prev.roles.filter((r) => r !== role),
    }));
  }

  const isSubmitting = createMutation.isPending || updateMutation.isPending;

  return (
    <div className="space-y-6">
      {/* Username (create only) */}
      {!isEdit && (
        <div className="space-y-2">
          <Label htmlFor="user-username">Username</Label>
          <Input
            id="user-username"
            value={form.username}
            onChange={(e) => {
              setForm((prev) => ({ ...prev, username: e.target.value }));
              if (errors.username) setErrors((prev) => ({ ...prev, username: undefined }));
            }}
            placeholder="Enter username"
          />
          {errors.username && (
            <p className="text-sm text-destructive">{errors.username}</p>
          )}
        </div>
      )}

      {/* Email */}
      <div className="space-y-2">
        <Label htmlFor="user-email">Email</Label>
        <Input
          id="user-email"
          type="email"
          value={form.email}
          onChange={(e) => {
            setForm((prev) => ({ ...prev, email: e.target.value }));
            if (errors.email) setErrors((prev) => ({ ...prev, email: undefined }));
          }}
          placeholder="user@example.com"
        />
        {errors.email && (
          <p className="text-sm text-destructive">{errors.email}</p>
        )}
      </div>

      {/* First Name */}
      <div className="space-y-2">
        <Label htmlFor="user-firstname">First Name</Label>
        <Input
          id="user-firstname"
          value={form.firstName}
          onChange={(e) => {
            setForm((prev) => ({ ...prev, firstName: e.target.value }));
            if (errors.firstName) setErrors((prev) => ({ ...prev, firstName: undefined }));
          }}
          placeholder="First name"
        />
        {errors.firstName && (
          <p className="text-sm text-destructive">{errors.firstName}</p>
        )}
      </div>

      {/* Last Name */}
      <div className="space-y-2">
        <Label htmlFor="user-lastname">Last Name</Label>
        <Input
          id="user-lastname"
          value={form.lastName}
          onChange={(e) => {
            setForm((prev) => ({ ...prev, lastName: e.target.value }));
            if (errors.lastName) setErrors((prev) => ({ ...prev, lastName: undefined }));
          }}
          placeholder="Last name"
        />
        {errors.lastName && (
          <p className="text-sm text-destructive">{errors.lastName}</p>
        )}
      </div>

      {!isEdit && (
        <div className="space-y-2">
          <Label htmlFor="user-client">Goods owner</Label>
          <select
            id="user-client"
            value={form.clientId}
            onChange={(event) => {
              setForm((prev) => ({ ...prev, clientId: event.target.value }));
              if (errors.clientId) setErrors((prev) => ({ ...prev, clientId: undefined }));
            }}
            disabled={clientsLoading}
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm"
          >
            <option value="">{clientsLoading ? 'Loading goods owners...' : 'Select a goods owner'}</option>
            {clients.filter((client) => client.state === 'ACTIVE').map((client) => (
              <option key={client.id} value={client.id}>
                {client.number} - {client.name}
              </option>
            ))}
          </select>
          {errors.clientId && <p className="text-sm text-destructive">{errors.clientId}</p>}
        </div>
      )}

      {!isEdit && (
        <div className="space-y-2">
          <Label htmlFor="user-principal-kind">Tenant authority</Label>
          <select
            id="user-principal-kind"
            value={form.principalKind}
            onChange={(event) => {
              setForm((prev) => ({
                ...prev,
                principalKind: event.target.value as '' | PrincipalKind,
              }));
              if (errors.principalKind) setErrors((prev) => ({ ...prev, principalKind: undefined }));
            }}
            className="flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm"
          >
            <option value="">Select tenant authority</option>
            <option value="owner">Goods owner - scoped to the selected owner</option>
            <option value="ops">Operations - spans every goods owner</option>
          </select>
          <p className="text-xs text-muted-foreground">
            Operations authority lets this user read and change every goods owner's data. Only an
            operations administrator may grant it.
          </p>
          {errors.principalKind && (
            <p className="text-sm text-destructive">{errors.principalKind}</p>
          )}
        </div>
      )}

      {/* Warehouse ID */}
      <div className="space-y-2">
        <Label htmlFor="user-warehouseid">Warehouse ID</Label>
        <Input
          id="user-warehouseid"
          value={form.warehouseId}
          onChange={(e) =>
            setForm((prev) => ({ ...prev, warehouseId: e.target.value }))
          }
          placeholder="e.g., WH-001"
        />
      </div>

      {/* Password (create only) */}
      {!isEdit && (
        <div className="space-y-2">
          <Label htmlFor="user-password">Password</Label>
          <Input
            id="user-password"
            type="password"
            value={form.password}
            onChange={(e) => {
              setForm((prev) => ({ ...prev, password: e.target.value }));
              if (errors.password) setErrors((prev) => ({ ...prev, password: undefined }));
            }}
            placeholder="Min 8 characters"
          />
          {errors.password && (
            <p className="text-sm text-destructive">{errors.password}</p>
          )}
        </div>
      )}

      {/* Force password change (create only) */}
      {!isEdit && (
        <div className="flex items-center gap-2">
          <Checkbox
            id="user-force-pw"
            checked={form.forcePasswordChange}
            onCheckedChange={(checked) =>
              setForm((prev) => ({ ...prev, forcePasswordChange: checked === true }))
            }
          />
          <Label htmlFor="user-force-pw">Force Password Change</Label>
        </div>
      )}

      {/* Roles */}
      <div className="space-y-3">
        <Label>Roles</Label>
        <div className="grid grid-cols-2 gap-2">
          {AVAILABLE_ROLES.map((role) => (
            <div key={role} className="flex items-center gap-2">
              <Checkbox
                id={`role-${role}`}
                checked={form.roles.includes(role)}
                onCheckedChange={(checked) =>
                  handleRoleToggle(role, checked === true)
                }
              />
              <Label htmlFor={`role-${role}`}>{role}</Label>
            </div>
          ))}
        </div>
      </div>

      {/* Submit button */}
      <div className="flex justify-end gap-2 pt-4 border-t">
        <Button variant="outline" onClick={onClose}>
          Cancel
        </Button>
        <Button onClick={handleSubmit} disabled={isSubmitting}>
          {isSubmitting ? 'Saving...' : 'Save'}
        </Button>
      </div>
    </div>
  );
}
