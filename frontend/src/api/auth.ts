import { request } from './client';
import type { LoginRequest, LoginResponse, RegisterRequest, UserResponse } from './types';

/** POST /api/auth/register — always creates a HOLDER. */
export function register(body: RegisterRequest): Promise<UserResponse> {
  return request<UserResponse>('POST', '/api/auth/register', { body, auth: false });
}

/** POST /api/auth/login */
export function login(body: LoginRequest): Promise<LoginResponse> {
  return request<LoginResponse>('POST', '/api/auth/login', { body, auth: false, redirectOn401: false });
}

/** GET /api/auth/me */
export function getMe(): Promise<UserResponse> {
  return request<UserResponse>('GET', '/api/auth/me');
}
