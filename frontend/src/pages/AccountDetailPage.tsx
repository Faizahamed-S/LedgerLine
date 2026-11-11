import { Link, useParams } from 'react-router-dom';
import * as accountsApi from '../api/accounts';
import * as statementApi from '../api/statement';
import type { AccountResponse, UserResponse } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { MoneyForm, type MoneyAction } from '../components/MoneyForm';
import { StatementTable } from '../components/StatementTable';
import { Badge, Card, ErrorState, LoadingState, Notice, StatusBadge } from '../components/ui';
import { useAsync } from '../hooks/useAsync';
import { formatDateTime } from '../lib/format';
import { formatPaise } from '../lib/money';

export function AccountDetailPage() {
  const { id } = useParams();
  const accountId = Number(id);
  const { user } = useAuth();

  const account = useAsync(() => accountsApi.getAccount(accountId), [accountId]);
  const statement = useAsync(() => statementApi.getStatement(accountId), [accountId]);

  if (!Number.isInteger(accountId) || accountId <= 0) {
    return <ErrorState message="That is not a valid account id." />;
  }

  function refresh() {
    account.reload();
    statement.reload();
  }

  const actions = account.data && user ? allowedActions(user, account.data) : [];

  return (
    <div className="space-y-6">
      <Link to="/accounts" className="text-sm text-slate-500 hover:text-slate-900">
        ← All accounts
      </Link>

      {account.loading && !account.data && <LoadingState />}
      {account.error && !account.data && <ErrorState message={account.error} onRetry={account.reload} />}

      {account.data && (
        <Card>
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div>
              <div className="flex items-center gap-2">
                <h1 className="text-2xl font-semibold text-slate-900">{account.data.name}</h1>
                <StatusBadge status={account.data.status} />
                {account.data.type === 'EXTERNAL' && <Badge tone="indigo">EXTERNAL</Badge>}
              </div>
              <p className="mt-1 text-sm text-slate-500">
                Account #{account.data.id}
                {account.data.ownerEmail && <> · owner {account.data.ownerEmail}</>} · opened{' '}
                {formatDateTime(account.data.createdAt)}
              </p>
            </div>
            <div className="text-right">
              <p className="text-xs uppercase tracking-wide text-slate-500">Balance</p>
              <p className="text-3xl font-semibold tabular-nums text-slate-900">{formatPaise(account.data.balancePaise)}</p>
            </div>
          </div>
          {account.data.status !== 'ACTIVE' && account.data.type === 'CUSTOMER' && (
            <div className="mt-4">
              <Notice tone="amber">
                This account is {account.data.status}. Money can only move on ACTIVE accounts.
              </Notice>
            </div>
          )}
        </Card>
      )}

      {actions.length > 0 && account.data && (
        <div className="grid gap-4 md:grid-cols-3">
          {actions.map((action) => (
            <MoneyForm key={action} action={action} accountId={account.data!.id} onCompleted={refresh} />
          ))}
        </div>
      )}

      <Card>
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-base font-semibold text-slate-900">Statement</h2>
          {statement.loading && statement.data && <span className="text-xs text-slate-400">Refreshing…</span>}
        </div>
        {statement.loading && !statement.data && <LoadingState />}
        {statement.error && <ErrorState message={statement.error} onRetry={statement.reload} />}
        {statement.data && <StatementTable lines={statement.data.lines} />}
      </Card>
    </div>
  );
}

/**
 * Which money forms to show. UX only: the server's AccountAccessPolicy makes the real decision and records a
 * refusal if this ever disagrees.
 */
function allowedActions(user: UserResponse, account: AccountResponse): MoneyAction[] {
  if (account.type !== 'CUSTOMER' || account.status !== 'ACTIVE') return [];
  if (user.role === 'HOLDER' && account.ownerId === user.id) return ['deposit', 'withdraw', 'transfer'];
  if (user.role === 'ADMIN') return ['deposit'];
  return [];
}
