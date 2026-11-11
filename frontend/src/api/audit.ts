import { request } from './client';
import type { AuditLogResponse, ReconciliationResponse } from './types';

/** GET /api/audit/log?limit= — newest first, the server caps limit at 500. */
export function getAuditLog(limit = 100): Promise<AuditLogResponse[]> {
  return request<AuditLogResponse[]>('GET', `/api/audit/log?limit=${limit}`);
}

/** GET /api/audit/reconciliation */
export function getReconciliation(): Promise<ReconciliationResponse> {
  return request<ReconciliationResponse>('GET', '/api/audit/reconciliation');
}
