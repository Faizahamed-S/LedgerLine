import { useRef, useState, type FormEvent } from 'react';
import { messageOf, NetworkError } from '../api/client';
import * as moneyApi from '../api/money';
import type { MoneyOutcome } from '../api/money';
import { rejectReasonText } from '../lib/format';
import { formatPaise, MAX_AMOUNT_PAISE, parseRupeesToPaise } from '../lib/money';
import { Button, Card, Notice, TextField } from './ui';

export type MoneyAction = 'deposit' | 'withdraw' | 'transfer';

const TITLES: Record<MoneyAction, string> = {
  deposit: 'Deposit',
  withdraw: 'Withdraw',
  transfer: 'Transfer',
};

/** One logical request: the same key and the same body are re-sent on Retry. */
interface PendingRequest {
  idempotencyKey: string;
  amountPaise: number;
  toAccountId: number | null;
}

/**
 * Idempotency rules:
 * - The key is created with crypto.randomUUID() when the form is first submitted, not on every click.
 * - While a request is in flight the button is disabled, so a double-click cannot send a second request.
 * - Network error or 5xx: the outcome is unknown, so the inputs stay locked and Retry re-sends the SAME key and
 *   body. The server then either does the work once or replays the original result.
 * - Only a definite answer (2xx or 4xx) clears the key; the next submission gets a new one.
 */
export function MoneyForm({ action, accountId, onCompleted }: { action: MoneyAction; accountId: number; onCompleted: () => void }) {
  const [amount, setAmount] = useState('');
  const [destination, setDestination] = useState('');
  const [inFlight, setInFlight] = useState(false);
  const [locked, setLocked] = useState(false);
  const [canRetry, setCanRetry] = useState(false);
  const [validationError, setValidationError] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<MoneyOutcome | null>(null);

  const pendingRef = useRef<PendingRequest | null>(null);
  const inFlightRef = useRef(false);

  function buildRequest(): PendingRequest | null {
    const amountPaise = parseRupeesToPaise(amount);
    if (amountPaise === null) {
      setValidationError('Enter an amount in rupees, for example 250 or 99.50 (at most two decimals).');
      return null;
    }
    if (amountPaise <= 0) {
      setValidationError('The amount must be more than zero.');
      return null;
    }
    if (amountPaise > MAX_AMOUNT_PAISE) {
      setValidationError(`The maximum for one request is ${formatPaise(MAX_AMOUNT_PAISE)}.`);
      return null;
    }
    let toAccountId: number | null = null;
    if (action === 'transfer') {
      if (!/^\d+$/.test(destination.trim())) {
        setValidationError('Enter the destination account id (a number).');
        return null;
      }
      toAccountId = Number(destination.trim());
      if (toAccountId === accountId) {
        setValidationError('You cannot transfer to the same account.');
        return null;
      }
    }
    setValidationError(null);
    return { idempotencyKey: crypto.randomUUID(), amountPaise, toAccountId };
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (inFlightRef.current) return;
    if (!pendingRef.current) {
      const built = buildRequest();
      if (!built) return;
      pendingRef.current = built;
      setLocked(true);
    }
    await sendPending();
  }

  async function sendPending() {
    const pending = pendingRef.current;
    if (!pending || inFlightRef.current) return;
    inFlightRef.current = true;
    setInFlight(true);
    setCanRetry(false);
    setFailure(null);
    setOutcome(null);
    try {
      const result = await callApi(pending);
      if (result.httpStatus >= 500) {
        setFailure(result.error ?? 'The server had a problem.');
        setCanRetry(true);
        return;
      }
      finish();
      setOutcome(result);
      if (result.transaction?.status === 'POSTED') {
        setAmount('');
        setDestination('');
      }
      onCompleted();
    } catch (error) {
      if (error instanceof NetworkError) {
        setFailure(error.message);
        setCanRetry(true);
      } else {
        finish();
        setFailure(messageOf(error));
      }
    } finally {
      inFlightRef.current = false;
      setInFlight(false);
    }
  }

  function finish() {
    pendingRef.current = null;
    setLocked(false);
  }

  function callApi(pending: PendingRequest): Promise<MoneyOutcome> {
    const body = { amountPaise: pending.amountPaise };
    switch (action) {
      case 'deposit':
        return moneyApi.deposit(accountId, body, pending.idempotencyKey);
      case 'withdraw':
        return moneyApi.withdraw(accountId, body, pending.idempotencyKey);
      case 'transfer':
        return moneyApi.transfer(
          { fromAccountId: accountId, toAccountId: pending.toAccountId ?? 0, amountPaise: pending.amountPaise },
          pending.idempotencyKey,
        );
    }
  }

  const inputsDisabled = inFlight || locked;

  return (
    <Card>
      <h3 className="text-base font-semibold text-slate-900">{TITLES[action]}</h3>
      <form onSubmit={handleSubmit} className="mt-3 space-y-3">
        <TextField
          label="Amount (₹)"
          inputMode="decimal"
          placeholder="0.00"
          value={amount}
          onChange={(e) => setAmount(e.target.value)}
          disabled={inputsDisabled}
          required
        />
        {action === 'transfer' && (
          <TextField
            label="Destination account id"
            inputMode="numeric"
            placeholder="e.g. 42"
            value={destination}
            onChange={(e) => setDestination(e.target.value)}
            disabled={inputsDisabled}
            required
          />
        )}

        {validationError && <p className="text-sm text-rose-600">{validationError}</p>}

        {!canRetry && (
          <Button type="submit" disabled={inFlight} className="w-full">
            {inFlight ? 'Processing...' : TITLES[action]}
          </Button>
        )}

        {canRetry && (
          <div className="space-y-2">
            <Notice tone="amber">
              {failure} We don't know if it went through, so the details are locked. Retry is safe: it re-sends the
              same request and can never move the money twice.
            </Notice>
            <Button type="button" onClick={() => void sendPending()} disabled={inFlight} className="w-full">
              {inFlight ? 'Processing...' : 'Retry'}
            </Button>
          </div>
        )}

        {!canRetry && failure && <Notice tone="red">{failure}</Notice>}
        {outcome && <OutcomeNotice outcome={outcome} />}
      </form>
    </Card>
  );
}

function OutcomeNotice({ outcome }: { outcome: MoneyOutcome }) {
  const replayNote = outcome.replayed && (
    <p className="mt-1 text-xs text-slate-500">This request was already processed — showing the original result.</p>
  );
  const transaction = outcome.transaction;

  if (!transaction) {
    return (
      <Notice tone="red">
        {outcome.error}
        {replayNote}
      </Notice>
    );
  }
  if (transaction.status === 'POSTED') {
    return (
      <Notice tone="green">
        Done: {formatPaise(transaction.amountPaise)} ({transaction.kind.toLowerCase()}), transaction #
        {transaction.transactionId}.
        {replayNote}
      </Notice>
    );
  }
  return (
    <Notice tone="grey">
      Refused: {rejectReasonText(transaction.rejectReason)}. Nothing was moved (transaction #{transaction.transactionId}
      ).
      {replayNote}
    </Notice>
  );
}
