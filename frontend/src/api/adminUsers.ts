import { request } from './client';
import type { AdminUserResponse, CreateUserRequest, UpdateUserStatusRequest } from './types';

/** GET /api/admin/users */
export function listUsers(): Promise<AdminUserResponse[]> {
  return request<AdminUserResponse[]>('GET', '/api/admin/users');
}

/** POST /api/admin/users */
export function createUser(body: CreateUserRequest): Promise<AdminUserResponse> {
  return request<AdminUserResponse>('POST', '/api/admin/users', { body });
}

/** PATCH /api/admin/users/{id}/status */
export function updateUserStatus(id: number, body: UpdateUserStatusRequest): Promise<AdminUserResponse> {
  return request<AdminUserResponse>('PATCH', `/api/admin/users/${id}/status`, { body });
}
