import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import * as accountsApi from '../api/accounts';
import { messageOf } from '../api/client';
import type { AccountResponse } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  ErrorState,
  LoadingState,
  Notice,
  PageHeader,
  StatusBadge,
  Table,
  Td,
  TextField,
} from '../components/ui';
import { useAsync } from '../hooks/useAsync';
import { formatPaise } from '../lib/money';

export function AccountsPage() {
  const { user } = useAuth();
  const accounts = useAsync(() => accountsApi.listAccounts(), []);
  const isHolder = user?.role === 'HOLDER';

  return (
    <div className="space-y-6">
      <PageHeader
        title={isHolder ? 'My accounts' : 'All accounts'}
        subtitle={isHolder ? 'Your accounts and balances.' : 'Every account in the system, including the EXTERNAL account.'}
      />

      {isHolder && <RequestAccountForm onCreated={accounts.reload} />}

      <Card>
        {accounts.loading && !accounts.data && <LoadingState />}
        {accounts.error && <ErrorState message={accounts.error} onRetry={accounts.reload} />}
        {accounts.data && (isHolder ? <HolderAccounts accounts={accounts.data} /> : <AllAccounts accounts={accounts.data} />)}
      </Card>
    </div>
  );
}

function HolderAccounts({ accounts }: { accounts: AccountResponse[] }) {
  if (accounts.length === 0) return <EmptyState>You have no accounts yet. Request one above.</EmptyState>;
  return (
    <Table head={['Account', 'Status', 'Balance']}>
      {accounts.map((account) => (
        <tr key={account.id}>
          <Td>
            <Link to={`/accounts/${account.id}`} className="font-medium text-indigo-600 hover:underline">
              {account.name}
            </Link>
            <span className="ml-2 text-xs text-slate-400">#{account.id}</span>
          </Td>
          <Td>
            <StatusBadge status={account.status} />
          </Td>
          <Td className="text-right font-medium tabular-nums">{formatPaise(account.balancePaise)}</Td>
        </tr>
      ))}
    </Table>
  );
}

function AllAccounts({ accounts }: { accounts: AccountResponse[] }) {
  if (accounts.length === 0) return <EmptyState>No accounts.</EmptyState>;
  return (
    <Table head={['#', 'Account', 'Type', 'Owner', 'Status', 'Balance']}>
      {accounts.map((account) => (
        <tr key={account.id} className={account.type === 'EXTERNAL' ? 'bg-slate-50' : ''}>
          <Td className="text-slate-400">{account.id}</Td>
          <Td>
            <Link to={`/accounts/${account.id}`} className="font-medium text-indigo-600 hover:underline">
              {account.name}
            </Link>
          </Td>
          <Td>{account.type === 'EXTERNAL' ? <Badge tone="indigo">EXTERNAL</Badge> : 'Customer'}</Td>
          <Td>{account.ownerEmail ?? '—'}</Td>
          <Td>
            <StatusBadge status={account.status} />
          </Td>
          <Td className="text-right font-medium tabular-nums">{formatPaise(account.balancePaise)}</Td>
        </tr>
      ))}
    </Table>
  );
}

function RequestAccountForm({ onCreated }: { onCreated: () => void }) {
  const [name, setName] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState<AccountResponse | null>(null);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    setCreated(null);
    try {
      const account = await accountsApi.requestAccount({ name: name.trim() });
      setCreated(account);
      setName('');
      onCreated();
    } catch (e) {
      setError(messageOf(e));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Card>
      <h2 className="text-base font-semibold text-slate-900">Request new account</h2>
      <form onSubmit={handleSubmit} className="mt-3 flex flex-wrap items-end gap-3">
        <div className="min-w-60 flex-1">
          <TextField label="Account name" maxLength={60} value={name} onChange={(e) => setName(e.target.value)} required />
        </div>
        <Button type="submit" disabled={submitting || name.trim() === ''}>
          {submitting ? 'Requesting…' : 'Request account'}
        </Button>
      </form>
      <div className="mt-3 space-y-2">
        {error && <ErrorState message={error} />}
        {created && (
          <Notice tone="green">
            “{created.name}” was requested. An admin must approve it before you can move money.
          </Notice>
        )}
      </div>
    </Card>
  );
}
