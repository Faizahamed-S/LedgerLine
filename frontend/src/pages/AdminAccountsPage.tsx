import { useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import * as adminAccountsApi from '../api/adminAccounts';
import { messageOf } from '../api/client';
import type { AccountResponse, AccountStatus } from '../api/types';
import { Button, Card, EmptyState, ErrorState, LoadingState, PageHeader, StatusBadge, Table, Td } from '../components/ui';
import { useAsync } from '../hooks/useAsync';
import { formatDateTime } from '../lib/format';
import { formatPaise } from '../lib/money';

export function AdminAccountsPage() {
  const pending = useAsync(() => adminAccountsApi.listCustomerAccounts('PENDING'), []);
  const live = useAsync(
    async () => {
      const [active, frozen] = await Promise.all([
        adminAccountsApi.listCustomerAccounts('ACTIVE'),
        adminAccountsApi.listCustomerAccounts('FROZEN'),
      ]);
      return [...active, ...frozen].sort((a, b) => a.id - b.id);
    },
    [],
  );
  const [busyId, setBusyId] = useState<number | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  async function changeStatus(account: AccountResponse, status: AccountStatus) {
    setBusyId(account.id);
    setActionError(null);
    try {
      await adminAccountsApi.updateAccountStatus(account.id, { status });
      pending.reload();
      live.reload();
    } catch (e) {
      setActionError(messageOf(e));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader title="Account approvals" subtitle="Approve new accounts, and freeze or unfreeze active ones." />
      {actionError && <ErrorState message={actionError} />}

      <Card>
        <h2 className="mb-3 text-base font-semibold text-slate-900">Waiting for approval</h2>
        {pending.loading && !pending.data && <LoadingState />}
        {pending.error && <ErrorState message={pending.error} onRetry={pending.reload} />}
        {pending.data && pending.data.length === 0 && <EmptyState>No accounts are waiting for approval.</EmptyState>}
        {pending.data && pending.data.length > 0 && (
          <AccountTable accounts={pending.data}>
            {(account) => (
              <Button disabled={busyId === account.id} onClick={() => void changeStatus(account, 'ACTIVE')}>
                {busyId === account.id ? 'Approving…' : 'Approve'}
              </Button>
            )}
          </AccountTable>
        )}
      </Card>

      <Card>
        <h2 className="mb-3 text-base font-semibold text-slate-900">Active and frozen accounts</h2>
        {live.loading && !live.data && <LoadingState />}
        {live.error && <ErrorState message={live.error} onRetry={live.reload} />}
        {live.data && live.data.length === 0 && <EmptyState>No active or frozen accounts.</EmptyState>}
        {live.data && live.data.length > 0 && (
          <AccountTable accounts={live.data}>
            {(account) =>
              account.status === 'ACTIVE' ? (
                <Button variant="danger" disabled={busyId === account.id} onClick={() => void changeStatus(account, 'FROZEN')}>
                  {busyId === account.id ? 'Freezing…' : 'Freeze'}
                </Button>
              ) : (
                <Button variant="secondary" disabled={busyId === account.id} onClick={() => void changeStatus(account, 'ACTIVE')}>
                  {busyId === account.id ? 'Unfreezing…' : 'Unfreeze'}
                </Button>
              )
            }
          </AccountTable>
        )}
      </Card>
    </div>
  );
}

function AccountTable({
  accounts,
  children,
}: {
  accounts: AccountResponse[];
  children: (account: AccountResponse) => ReactNode;
}) {
  return (
    <Table head={['#', 'Account', 'Owner', 'Status', 'Balance', 'Requested', '']}>
      {accounts.map((account) => (
        <tr key={account.id}>
          <Td className="text-slate-400">{account.id}</Td>
          <Td>
            <Link to={`/accounts/${account.id}`} className="font-medium text-indigo-600 hover:underline">
              {account.name}
            </Link>
          </Td>
          <Td>{account.ownerEmail ?? '—'}</Td>
          <Td>
            <StatusBadge status={account.status} />
          </Td>
          <Td className="text-right tabular-nums">{formatPaise(account.balancePaise)}</Td>
          <Td>{formatDateTime(account.createdAt)}</Td>
          <Td className="text-right">{children(account)}</Td>
        </tr>
      ))}
    </Table>
  );
}
