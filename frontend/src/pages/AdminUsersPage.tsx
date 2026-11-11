import { useState, type FormEvent } from 'react';
import * as adminUsersApi from '../api/adminUsers';
import { messageOf } from '../api/client';
import type { AdminUserResponse, Role } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import {
  Button,
  Card,
  EmptyState,
  ErrorState,
  LoadingState,
  Notice,
  PageHeader,
  RoleBadge,
  SelectField,
  StatusBadge,
  Table,
  Td,
  TextField,
} from '../components/ui';
import { useAsync } from '../hooks/useAsync';
import { formatDateTime } from '../lib/format';

export function AdminUsersPage() {
  const { user: me } = useAuth();
  const users = useAsync(() => adminUsersApi.listUsers(), []);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  async function toggleStatus(target: AdminUserResponse) {
    setBusyId(target.id);
    setActionError(null);
    try {
      await adminUsersApi.updateUserStatus(target.id, { status: target.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE' });
      users.reload();
    } catch (e) {
      setActionError(messageOf(e));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader title="Users" subtitle="Create users with any role, and enable or disable them." />
      <CreateUserForm onCreated={users.reload} />
      <Card>
        {actionError && (
          <div className="mb-3">
            <ErrorState message={actionError} />
          </div>
        )}
        {users.loading && !users.data && <LoadingState />}
        {users.error && <ErrorState message={users.error} onRetry={users.reload} />}
        {users.data && users.data.length === 0 && <EmptyState>No users.</EmptyState>}
        {users.data && users.data.length > 0 && (
          <Table head={['Email', 'Name', 'Role', 'Status', 'Created', '']}>
            {users.data.map((u) => (
              <tr key={u.id}>
                <Td className="font-medium text-slate-900">{u.email}</Td>
                <Td>{u.fullName}</Td>
                <Td>
                  <RoleBadge role={u.role} />
                </Td>
                <Td>
                  <StatusBadge status={u.status} />
                </Td>
                <Td>{formatDateTime(u.createdAt)}</Td>
                <Td className="text-right">
                  {u.id === me?.id ? (
                    <span className="text-xs text-slate-400">you</span>
                  ) : (
                    <Button
                      variant={u.status === 'ACTIVE' ? 'danger' : 'secondary'}
                      disabled={busyId === u.id}
                      onClick={() => void toggleStatus(u)}
                    >
                      {busyId === u.id ? 'Saving…' : u.status === 'ACTIVE' ? 'Disable' : 'Enable'}
                    </Button>
                  )}
                </Td>
              </tr>
            ))}
          </Table>
        )}
      </Card>
    </div>
  );
}

const ROLES: Role[] = ['HOLDER', 'AUDITOR', 'ADMIN'];

function CreateUserForm({ onCreated }: { onCreated: () => void }) {
  const [email, setEmail] = useState('');
  const [fullName, setFullName] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState<Role>('HOLDER');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState<AdminUserResponse | null>(null);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    setCreated(null);
    try {
      const user = await adminUsersApi.createUser({ email, fullName, password, role });
      setCreated(user);
      setEmail('');
      setFullName('');
      setPassword('');
      setRole('HOLDER');
      onCreated();
    } catch (e) {
      setError(messageOf(e));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Card>
      <h2 className="text-base font-semibold text-slate-900">Create user</h2>
      <form onSubmit={handleSubmit} className="mt-3 grid gap-3 md:grid-cols-5 md:items-end">
        <TextField label="Email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        <TextField label="Full name" value={fullName} onChange={(e) => setFullName(e.target.value)} required />
        <TextField
          label="Password"
          type="password"
          autoComplete="new-password"
          minLength={8}
          maxLength={72}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
        />
        <SelectField label="Role" value={role} onChange={(e) => setRole(e.target.value as Role)}>
          {ROLES.map((r) => (
            <option key={r} value={r}>
              {r}
            </option>
          ))}
        </SelectField>
        <Button type="submit" disabled={submitting}>
          {submitting ? 'Creating…' : 'Create user'}
        </Button>
      </form>
      <div className="mt-3 space-y-2">
        {error && <ErrorState message={error} />}
        {created && (
          <Notice tone="green">
            Created {created.email} as {created.role}.
          </Notice>
        )}
      </div>
    </Card>
  );
}
