import { useState } from 'react';
import * as auditApi from '../api/audit';
import type { ReconciliationResponse } from '../api/types';
import { Button, Card, EmptyState, ErrorState, LoadingState, PageHeader, SelectField, Table, Td } from '../components/ui';
import { useAsync } from '../hooks/useAsync';
import { formatDateTime } from '../lib/format';
import { formatPaise } from '../lib/money';

const LIMITS = [50, 100, 500];

export function AuditPage() {
  const reconciliation = useAsync(() => auditApi.getReconciliation(), []);
  const [limit, setLimit] = useState(100);
  const log = useAsync(() => auditApi.getAuditLog(limit), [limit]);

  return (
    <div className="space-y-6">
      <PageHeader
        title="Audit"
        subtitle="Read-only. Checks that the books balance, and shows admin and security actions."
        actions={
          <Button variant="secondary" onClick={() => { reconciliation.reload(); log.reload(); }}>
            Refresh
          </Button>
        }
      />

      {reconciliation.loading && !reconciliation.data && (
        <Card>
          <LoadingState label="Checking the books…" />
        </Card>
      )}
      {reconciliation.error && <ErrorState message={reconciliation.error} onRetry={reconciliation.reload} />}
      {reconciliation.data && <ReconciliationCard data={reconciliation.data} />}

      <Card>
        <div className="mb-3 flex flex-wrap items-end justify-between gap-3">
          <h2 className="text-base font-semibold text-slate-900">Audit log</h2>
          <div className="w-32">
            <SelectField label="Show" value={limit} onChange={(e) => setLimit(Number(e.target.value))}>
              {LIMITS.map((value) => (
                <option key={value} value={value}>
                  {value} rows
                </option>
              ))}
            </SelectField>
          </div>
        </div>
        {log.loading && !log.data && <LoadingState />}
        {log.error && <ErrorState message={log.error} onRetry={log.reload} />}
        {log.data && log.data.length === 0 && <EmptyState>The audit log is empty.</EmptyState>}
        {log.data && log.data.length > 0 && (
          <Table head={['When', 'Actor', 'Action', 'Target', 'Details']}>
            {log.data.map((row) => (
              <tr key={row.id}>
                <Td>{formatDateTime(row.createdAt)}</Td>
                <Td>{row.actorEmail ?? <span className="text-slate-400">system / anonymous</span>}</Td>
                <Td className="font-medium text-slate-900">{row.action}</Td>
                <Td>{row.targetType ? `${row.targetType} #${row.targetId ?? '?'}` : '—'}</Td>
                <Td>
                  {row.details ? (
                    <code className="block max-w-md truncate rounded bg-slate-100 px-1.5 py-0.5 text-xs text-slate-700" title={row.details}>
                      {row.details}
                    </code>
                  ) : (
                    '—'
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

function ReconciliationCard({ data }: { data: ReconciliationResponse }) {
  return (
    <Card className={data.balanced ? 'border-emerald-200' : 'border-rose-300'}>
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <p className={`text-3xl font-bold ${data.balanced ? 'text-emerald-600' : 'text-rose-600'}`}>
            {data.balanced ? '✓ Books balance' : '✗ NOT BALANCED'}
          </p>
          <p className="mt-1 text-xs text-slate-500">Checked {formatDateTime(data.checkedAt)}</p>
        </div>
        <dl className="grid grid-cols-2 gap-x-8 gap-y-2 text-sm md:grid-cols-3">
          <Stat label="Sum of all ledger entries" value={formatPaise(data.ledgerSumPaise)} bad={data.ledgerSumPaise !== 0} />
          <Stat label="Customer balances" value={formatPaise(data.customerTotalPaise)} />
          <Stat label="EXTERNAL balance" value={formatPaise(data.externalBalancePaise)} />
          <Stat label="Posted transactions" value={data.postedCount.toLocaleString('en-IN')} />
          <Stat label="Refused requests" value={data.rejectedCount.toLocaleString('en-IN')} />
          <Stat label="Duplicate requests caught" value={data.duplicateRequestCount.toLocaleString('en-IN')} />
        </dl>
      </div>

      {data.mismatchedAccounts.length > 0 && (
        <div className="mt-4">
          <h3 className="mb-2 text-sm font-semibold text-rose-700">Accounts whose balance does not match their ledger</h3>
          <Table head={['Account', 'Balance', 'Sum of ledger entries', 'Difference']}>
            {data.mismatchedAccounts.map((m) => (
              <tr key={m.accountId}>
                <Td>#{m.accountId}</Td>
                <Td className="tabular-nums">{formatPaise(m.balancePaise)}</Td>
                <Td className="tabular-nums">{formatPaise(m.ledgerSumPaise)}</Td>
                <Td className="tabular-nums text-rose-600">{formatPaise(m.balancePaise - m.ledgerSumPaise)}</Td>
              </tr>
            ))}
          </Table>
        </div>
      )}
    </Card>
  );
}

function Stat({ label, value, bad = false }: { label: string; value: string; bad?: boolean }) {
  return (
    <div>
      <dt className="text-xs text-slate-500">{label}</dt>
      <dd className={`font-semibold tabular-nums ${bad ? 'text-rose-600' : 'text-slate-900'}`}>{value}</dd>
    </div>
  );
}
