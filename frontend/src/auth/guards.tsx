import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import type { Role } from '../api/types';
import { Card, LoadingState } from '../components/ui';
import { useAuth } from './AuthContext';

// These guards are UX only: they hide pages a role cannot use. They are NOT security. The server checks the
// token and enforces every permission on every request, so a user who bypasses a guard still gets 401/403/404.

export function RequireAuth({ children }: { children: ReactNode }) {
  const { user, initializing } = useAuth();
  const location = useLocation();
  if (initializing) return <LoadingState label="Checking your session…" />;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return <>{children}</>;
}

export function RequireRole({ roles, children }: { roles: Role[]; children: ReactNode }) {
  const { user } = useAuth();
  if (!user || !roles.includes(user.role)) {
    return (
      <Card>
        <h1 className="text-lg font-semibold text-slate-900">Not available</h1>
        <p className="mt-1 text-sm text-slate-600">This page is not available for your role.</p>
      </Card>
    );
  }
  return <>{children}</>;
}
