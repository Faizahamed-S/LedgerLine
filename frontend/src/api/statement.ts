import { request } from './client';
import type { StatementResponse } from './types';

/** GET /api/accounts/{id}/statement — current balance plus up to 500 lines, newest first. */
export function getStatement(accountId: number): Promise<StatementResponse> {
  return request<StatementResponse>('GET', `/api/accounts/${accountId}/statement`);
}
