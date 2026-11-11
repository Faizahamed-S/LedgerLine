import { errorMessage, send } from './client';
import type { AmountRequest, TransactionResponse, TransferRequest } from './types';

/**
 * The answer to a money request. A refusal is a normal answer, not an error: the server records it and returns
 * the REJECTED transaction with a 403/404/409/422 status. Throws NetworkError when there was no answer at all.
 */
export interface MoneyOutcome {
  httpStatus: number;
  /** Present for POSTED and REJECTED results. */
  transaction: TransactionResponse | null;
  /** Present when the server answered with an error instead of a transaction (400, 409 key reuse, 429, 5xx). */
  error: string | null;
  /** The server already had this Idempotency-Key and returned the original result. */
  replayed: boolean;
}

/** POST /api/accounts/{id}/deposits */
export function deposit(accountId: number, body: AmountRequest, idempotencyKey: string): Promise<MoneyOutcome> {
  return moneyRequest(`/api/accounts/${accountId}/deposits`, body, idempotencyKey);
}

/** POST /api/accounts/{id}/withdrawals */
export function withdraw(accountId: number, body: AmountRequest, idempotencyKey: string): Promise<MoneyOutcome> {
  return moneyRequest(`/api/accounts/${accountId}/withdrawals`, body, idempotencyKey);
}

/** POST /api/transfers */
export function transfer(body: TransferRequest, idempotencyKey: string): Promise<MoneyOutcome> {
  return moneyRequest('/api/transfers', body, idempotencyKey);
}

async function moneyRequest(path: string, body: unknown, idempotencyKey: string): Promise<MoneyOutcome> {
  const response = await send('POST', path, { body, idempotencyKey });
  const transaction = isTransaction(response.body) ? response.body : null;
  return {
    httpStatus: response.status,
    transaction,
    error: transaction ? null : errorMessage(response),
    replayed: response.replayed,
  };
}

function isTransaction(body: unknown): body is TransactionResponse {
  return typeof body === 'object' && body !== null && 'transactionId' in body;
}
