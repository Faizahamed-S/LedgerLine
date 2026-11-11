import { request } from './client';
import type { AccountResponse, CreateAccountRequest } from './types';

/** GET /api/accounts — HOLDER: own accounts. ADMIN/AUDITOR: all, including EXTERNAL. */
export function listAccounts(): Promise<AccountResponse[]> {
  return request<AccountResponse[]>('GET', '/api/accounts');
}

/** GET /api/accounts/{id} */
export function getAccount(id: number): Promise<AccountResponse> {
  return request<AccountResponse>('GET', `/api/accounts/${id}`);
}

/** POST /api/accounts — HOLDER only; the new account starts PENDING. */
export function requestAccount(body: CreateAccountRequest): Promise<AccountResponse> {
  return request<AccountResponse>('POST', '/api/accounts', { body });
}
