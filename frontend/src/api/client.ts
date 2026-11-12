import { API_URL } from '../config';
import type { ApiError, UserResponse } from './types';

export { API_URL };

const TOKEN_KEY = 'ledgerline.token';
const USER_KEY = 'ledgerline.user';

/** sessionStorage, not localStorage: the session ends when the tab closes and is not shared across tabs. */
export const session = {
  getToken(): string | null {
    return sessionStorage.getItem(TOKEN_KEY);
  },
  setToken(token: string): void {
    sessionStorage.setItem(TOKEN_KEY, token);
  },
  getUser(): UserResponse | null {
    const raw = sessionStorage.getItem(USER_KEY);
    if (!raw) return null;
    try {
      return JSON.parse(raw) as UserResponse;
    } catch {
      return null;
    }
  },
  setUser(user: UserResponse): void {
    sessionStorage.setItem(USER_KEY, JSON.stringify(user));
  },
  clear(): void {
    sessionStorage.removeItem(TOKEN_KEY);
    sessionStorage.removeItem(USER_KEY);
  },
};

/** The request never got an HTTP answer (offline, DNS, CORS, server down). The outcome is unknown. */
export class NetworkError extends Error {
  constructor() {
    super('Could not reach the server. Check your connection and try again.');
    this.name = 'NetworkError';
  }
}

/** The server answered with a non-2xx status. */
export class ApiRequestError extends Error {
  readonly status: number;
  readonly body: unknown;
  readonly retryAfterSeconds: number | null;

  constructor(status: number, message: string, body: unknown, retryAfterSeconds: number | null) {
    super(message);
    this.name = 'ApiRequestError';
    this.status = status;
    this.body = body;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

export interface RawResponse {
  status: number;
  ok: boolean;
  body: unknown;
  replayed: boolean;
  retryAfterSeconds: number | null;
}

export interface RequestOptions {
  body?: unknown;
  idempotencyKey?: string;
  /** Send the bearer token. Off for login/register so a stale token cannot get them rejected. */
  auth?: boolean;
  /** On 401, clear the session and go to /login. Off for login, where 401 means "wrong password". */
  redirectOn401?: boolean;
}

export function isApiError(body: unknown): body is ApiError {
  return typeof body === 'object' && body !== null && 'message' in body && 'status' in body;
}

export function errorMessage(response: RawResponse): string {
  if (response.status === 429) {
    const wait = response.retryAfterSeconds ?? 60;
    return `Too many requests. Try again in ${wait} seconds.`;
  }
  if (isApiError(response.body)) return response.body.message;
  if (response.status >= 500) return 'The server had a problem. Please try again.';
  return `Request failed (HTTP ${response.status}).`;
}

/** The one place that talks to the backend. Throws NetworkError only when there is no HTTP answer at all. */
export async function send(method: string, path: string, options: RequestOptions = {}): Promise<RawResponse> {
  if (!API_URL) {
    throw new Error('This app is not configured with a backend URL (VITE_API_URL). Please contact the operator.');
  }
  const headers: Record<string, string> = { Accept: 'application/json' };
  const token = session.getToken();
  if (token && options.auth !== false) headers.Authorization = `Bearer ${token}`;
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey;

  let response: Response;
  try {
    response = await fetch(`${API_URL}${path}`, {
      method,
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    });
  } catch {
    throw new NetworkError();
  }

  const body = await readJson(response);
  const result: RawResponse = {
    status: response.status,
    ok: response.ok,
    body,
    replayed: response.headers.get('Idempotent-Replayed') === 'true',
    retryAfterSeconds: parseRetryAfter(response.headers.get('Retry-After')),
  };

  if (response.status === 401 && options.redirectOn401 !== false) {
    session.clear();
    if (window.location.pathname !== '/login') window.location.assign('/login');
  }
  return result;
}

/** For endpoints whose only useful answer is 2xx: returns the body or throws ApiRequestError. */
export async function request<T>(method: string, path: string, options: RequestOptions = {}): Promise<T> {
  const response = await send(method, path, options);
  if (!response.ok) {
    throw new ApiRequestError(response.status, errorMessage(response), response.body, response.retryAfterSeconds);
  }
  return response.body as T;
}

export function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : 'Something went wrong.';
}

async function readJson(response: Response): Promise<unknown> {
  const text = await response.text();
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function parseRetryAfter(value: string | null): number | null {
  if (!value) return null;
  const seconds = Number.parseInt(value, 10);
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : null;
}
