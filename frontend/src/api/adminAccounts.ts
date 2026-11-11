import { request } from './client';
import type { AccountResponse, AccountStatus, UpdateAccountStatusRequest } from './types';

/** GET /api/admin/accounts?status= — CUSTOMER accounts with that status. */
export function listCustomerAccounts(status: AccountStatus): Promise<AccountResponse[]> {
  return request<AccountResponse[]>('GET', `/api/admin/accounts?status=${status}`);
}

/** PATCH /api/admin/accounts/{id}/status — approve, freeze or unfreeze. */
export function updateAccountStatus(id: number, body: UpdateAccountStatusRequest): Promise<AccountResponse> {
  return request<AccountResponse>('PATCH', `/api/admin/accounts/${id}/status`, { body });
}
