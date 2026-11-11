import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import * as authApi from '../api/auth';
import { ApiRequestError, session } from '../api/client';
import type { RegisterRequest, UserResponse } from '../api/types';

interface AuthContextValue {
  user: UserResponse | null;
  /** True while a stored session is being re-checked against GET /api/auth/me. */
  initializing: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (request: RegisterRequest) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserResponse | null>(() => (session.getToken() ? session.getUser() : null));
  const [initializing, setInitializing] = useState<boolean>(() => session.getToken() !== null);

  // The role and status live in the database, so refresh the stored user on every page load.
  useEffect(() => {
    if (!session.getToken()) return;
    let cancelled = false;
    authApi
      .getMe()
      .then((me) => {
        if (cancelled) return;
        session.setUser(me);
        setUser(me);
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        if (error instanceof ApiRequestError && error.status === 401) {
          session.clear();
          setUser(null);
        }
      })
      .finally(() => {
        if (!cancelled) setInitializing(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    session.clear();
    const response = await authApi.login({ email, password });
    session.setToken(response.token);
    const me = await authApi.getMe();
    session.setUser(me);
    setUser(me);
  }, []);

  const register = useCallback(
    async (request: RegisterRequest) => {
      await authApi.register(request);
      await login(request.email, request.password);
    },
    [login],
  );

  const logout = useCallback(() => {
    session.clear();
    setUser(null);
  }, []);

  const value = useMemo(
    () => ({ user, initializing, login, register, logout }),
    [user, initializing, login, register, logout],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used inside <AuthProvider>');
  return context;
}
