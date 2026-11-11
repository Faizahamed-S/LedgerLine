import { request } from './client';
import type { FlagResponse, FlagStatus, ReviewFlagRequest } from './types';

/** GET /api/audit/flags?status= — ADMIN and AUDITOR. Omit status for all flags. */
export function listFlags(status?: FlagStatus): Promise<FlagResponse[]> {
  return request<FlagResponse[]>('GET', status ? `/api/audit/flags?status=${status}` : '/api/audit/flags');
}

/** POST /api/admin/flags/{id}/review — ADMIN only, once per flag. Records a decision; never acts on money. */
export function reviewFlag(id: number, body: ReviewFlagRequest): Promise<FlagResponse> {
  return request<FlagResponse>('POST', `/api/admin/flags/${id}/review`, { body });
}
