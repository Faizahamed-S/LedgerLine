import { useState } from 'react';
import { Link } from 'react-router-dom';
import { messageOf } from '../api/client';
import * as flagsApi from '../api/flags';
import type { FlagDecision, FlagResponse, FlagSeverity, FlagStatus } from '../api/types';
import { useAsync } from '../hooks/useAsync';
import { formatDateTime } from '../lib/format';
import { Badge, Button, Card, EmptyState, ErrorState, LoadingState, SelectField, Table, Td } from './ui';

const SEVERITY_TONE: Record<FlagSeverity, 'red' | 'amber' | 'grey'> = {
  HIGH: 'red',
  MEDIUM: 'amber',
  LOW: 'grey',
};

type Filter = FlagStatus | 'ALL';

/**
 * Rule-based anomaly flags (no ML/LLM). The machine suggests; an ADMIN records a decision. A decision never
 * freezes an account or moves money: that stays a separate, deliberate admin action.
 */
export function FlagsCard({ canReview, refreshToken }: { canReview: boolean; refreshToken: number }) {
  const [filter, setFilter] = useState<Filter>('OPEN');
  const flags = useAsync(() => flagsApi.listFlags(filter === 'ALL' ? undefined : filter), [filter, refreshToken]);

  return (
    <Card>
      <div className="mb-3 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 className="text-base font-semibold text-slate-900">Anomaly flags</h2>
          <p className="text-xs text-slate-500">
            Rule-based anomaly flags (no ML/LLM). Suggestions only: reviewing a flag never freezes an account or moves
            money.
          </p>
        </div>
        <div className="w-36">
          <SelectField label="Show" value={filter} onChange={(e) => setFilter(e.target.value as Filter)}>
            <option value="OPEN">Open</option>
            <option value="REVIEWED">Reviewed</option>
            <option value="ALL">All</option>
          </SelectField>
        </div>
      </div>

      {flags.loading && !flags.data && <LoadingState />}
      {flags.error && <ErrorState message={flags.error} onRetry={flags.reload} />}
      {flags.data && flags.data.length === 0 && <EmptyState>No flags.</EmptyState>}
      {flags.data && flags.data.length > 0 && (
        <Table head={['Severity', 'Rule', 'Account', 'Transaction', 'Message', 'Time', 'Review']}>
          {flags.data.map((flag) => (
            <tr key={flag.id} className="align-top">
              <Td>
                <Badge tone={SEVERITY_TONE[flag.severity]}>{flag.severity}</Badge>
              </Td>
              <Td className="font-medium text-slate-900">{flag.rule}</Td>
              <Td>
                <Link to={`/accounts/${flag.accountId}`} className="text-indigo-600 hover:underline">
                  #{flag.accountId}
                </Link>
              </Td>
              <Td>{flag.transactionId === null ? '—' : `#${flag.transactionId}`}</Td>
              <Td wrap className="min-w-56 max-w-xs">
                {flag.message}
              </Td>
              <Td>{formatDateTime(flag.createdAt)}</Td>
              <Td wrap className="min-w-64">
                {flag.review ? (
                  <ReviewSummary flag={flag} />
                ) : canReview ? (
                  <ReviewForm flagId={flag.id} onReviewed={flags.reload} />
                ) : (
                  <span className="text-xs text-slate-400">Open</span>
                )}
              </Td>
            </tr>
          ))}
        </Table>
      )}
    </Card>
  );
}

function ReviewSummary({ flag }: { flag: FlagResponse }) {
  const review = flag.review!;
  return (
    <div className="space-y-1 text-xs">
      <Badge tone={review.decision === 'CONFIRMED' ? 'red' : 'green'}>{review.decision}</Badge>
      <p className="text-slate-600">{review.note}</p>
      <p className="text-slate-400">
        {review.reviewerEmail} · {formatDateTime(review.reviewedAt)}
      </p>
    </div>
  );
}

function ReviewForm({ flagId, onReviewed }: { flagId: number; onReviewed: () => void }) {
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState<FlagDecision | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function submit(decision: FlagDecision) {
    if (note.trim() === '') {
      setError('A note is required.');
      return;
    }
    setBusy(decision);
    setError(null);
    try {
      await flagsApi.reviewFlag(flagId, { decision, note: note.trim() });
      onReviewed();
    } catch (e) {
      setError(messageOf(e));
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="space-y-2">
      <textarea
        value={note}
        onChange={(e) => setNote(e.target.value)}
        maxLength={500}
        rows={2}
        placeholder="Note (required)"
        aria-label="Review note"
        className="block w-full rounded-lg border border-slate-300 px-2 py-1 text-xs focus:border-indigo-500 focus:outline-none focus:ring-2 focus:ring-indigo-200"
      />
      <div className="flex gap-2">
        <Button variant="secondary" disabled={busy !== null} onClick={() => void submit('DISMISSED')} className="px-2.5 py-1 text-xs">
          {busy === 'DISMISSED' ? 'Saving…' : 'Dismiss'}
        </Button>
        <Button variant="danger" disabled={busy !== null} onClick={() => void submit('CONFIRMED')} className="px-2.5 py-1 text-xs">
          {busy === 'CONFIRMED' ? 'Saving…' : 'Confirm'}
        </Button>
      </div>
      {error && <p className="text-xs text-rose-600">{error}</p>}
    </div>
  );
}
