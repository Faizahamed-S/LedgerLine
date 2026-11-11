import type { StatementLine } from '../api/types';
import { formatDateTime, rejectReasonText } from '../lib/format';
import { formatPaise } from '../lib/money';
import { EmptyState, Table, Td } from './ui';

const DIRECTION_STYLES = {
  DEBIT: 'text-rose-600',
  CREDIT: 'text-emerald-600',
  REFUSED: 'text-slate-400',
} as const;

export function StatementTable({ lines }: { lines: StatementLine[] }) {
  if (lines.length === 0) return <EmptyState>No transactions yet.</EmptyState>;

  return (
    <Table head={['Date', 'Kind', 'Direction', 'Amount', 'Balance after', 'Counterparty', 'Initiated by']}>
      {lines.map((line) => (
        <tr key={`${line.transactionId}-${line.direction}`} className={line.direction === 'REFUSED' ? 'bg-slate-50/60' : ''}>
          <Td>{formatDateTime(line.createdAt)}</Td>
          <Td>{line.kind}</Td>
          <Td className={`font-medium ${DIRECTION_STYLES[line.direction]}`}>
            {line.direction}
            {line.direction === 'REFUSED' && (
              <span className="ml-1 font-normal text-slate-500">· {rejectReasonText(line.rejectReason)}</span>
            )}
          </Td>
          <Td className={`text-right tabular-nums ${DIRECTION_STYLES[line.direction]}`}>
            {line.direction === 'DEBIT' ? '−' : line.direction === 'CREDIT' ? '+' : ''}
            {formatPaise(line.amountPaise)}
          </Td>
          <Td className="text-right tabular-nums">
            {line.balanceAfterPaise === null ? '—' : formatPaise(line.balanceAfterPaise)}
          </Td>
          <Td>{counterpartyLabel(line)}</Td>
          <Td>{line.initiatedByEmail}</Td>
        </tr>
      ))}
    </Table>
  );
}

function counterpartyLabel(line: StatementLine): string {
  if (line.kind === 'DEPOSIT' || line.kind === 'WITHDRAWAL') return 'External world';
  return line.counterpartyAccountId === null ? '—' : `Account #${line.counterpartyAccountId}`;
}
