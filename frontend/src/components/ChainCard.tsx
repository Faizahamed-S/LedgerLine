import * as auditApi from '../api/audit';
import { useAsync } from '../hooks/useAsync';
import { formatDateTime } from '../lib/format';
import { Card, ErrorState, LoadingState, Table, Td } from './ui';

/** Result of GET /api/audit/chain. {@code refreshToken} changes force a re-check. */
export function ChainCard({ refreshToken }: { refreshToken: number }) {
  const chain = useAsync(() => auditApi.getChainVerification(), [refreshToken]);

  if (chain.loading && !chain.data) {
    return (
      <Card>
        <LoadingState label="Verifying the ledger hash chain…" />
      </Card>
    );
  }
  if (chain.error) return <ErrorState message={chain.error} onRetry={chain.reload} />;
  if (!chain.data) return null;

  const data = chain.data;
  return (
    <Card className={data.intact ? 'border-emerald-200' : 'border-rose-300'}>
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <p className={`text-xl font-bold ${data.intact ? 'text-emerald-600' : 'text-rose-600'}`}>
            Ledger chain: {data.intact ? 'intact' : 'BROKEN'}
          </p>
          <p className="mt-1 text-xs text-slate-500">
            Every ledger entry's SHA-256 hash covers the previous entry of the same account, so any edit, insertion or
            deletion — even directly in the database — breaks the chain. Checked {formatDateTime(data.checkedAt)}.
          </p>
        </div>
        <dl className="grid grid-cols-3 gap-x-6 text-sm">
          <Stat label="Accounts" value={data.accountsChecked} />
          <Stat label="Entries" value={data.entriesChecked} />
          <Stat label="Pre-chain entries" value={data.unchainedLegacyEntries} />
        </dl>
      </div>

      {data.breaks.length > 0 && (
        <div className="mt-4">
          <h3 className="mb-2 text-sm font-semibold text-rose-700">Breaks</h3>
          <Table head={['Account', 'Entry', 'Problem', 'Expected', 'Stored']}>
            {data.breaks.map((b) => (
              <tr key={`${b.accountId}-${b.entryId}-${b.problem}`}>
                <Td>#{b.accountId}</Td>
                <Td>#{b.entryId}</Td>
                <Td className="font-medium text-rose-600">{b.problem}</Td>
                <Td>
                  <code className="text-xs">{shortHash(b.expectedHash)}</code>
                </Td>
                <Td>
                  <code className="text-xs">{shortHash(b.storedHash)}</code>
                </Td>
              </tr>
            ))}
          </Table>
        </div>
      )}
    </Card>
  );
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <dt className="text-xs text-slate-500">{label}</dt>
      <dd className="font-semibold tabular-nums text-slate-900">{value.toLocaleString('en-IN')}</dd>
    </div>
  );
}

function shortHash(hash: string | null): string {
  if (!hash) return '—';
  return hash.length > 16 ? `${hash.slice(0, 16)}…` : hash;
}
