import type { RejectReason } from '../api/types';

const dateTime = new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' });

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : dateTime.format(date);
}

const REJECT_REASON_TEXT: Record<RejectReason, string> = {
  NOT_AUTHORISED: 'Not allowed on this account',
  ACCOUNT_NOT_ACTIVE: 'Account is not active',
  DESTINATION_UNAVAILABLE: 'Destination account not available',
  INSUFFICIENT_FUNDS: 'Insufficient funds',
};

export function rejectReasonText(reason: string | null | undefined): string {
  if (!reason) return 'Refused';
  return REJECT_REASON_TEXT[reason as RejectReason] ?? reason;
}
