import { Navigate, Route, Routes } from 'react-router-dom';
import { RequireAuth, RequireRole } from './auth/guards';
import { Layout } from './components/Layout';
import { AccountDetailPage } from './pages/AccountDetailPage';
import { AccountsPage } from './pages/AccountsPage';
import { AdminAccountsPage } from './pages/AdminAccountsPage';
import { AdminUsersPage } from './pages/AdminUsersPage';
import { AuditPage } from './pages/AuditPage';
import { LoginPage } from './pages/LoginPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { RegisterPage } from './pages/RegisterPage';

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />
      <Route
        element={
          <RequireAuth>
            <Layout />
          </RequireAuth>
        }
      >
        <Route path="/" element={<Navigate to="/accounts" replace />} />
        <Route path="/accounts" element={<AccountsPage />} />
        <Route path="/accounts/:id" element={<AccountDetailPage />} />
        <Route
          path="/admin/users"
          element={
            <RequireRole roles={['ADMIN']}>
              <AdminUsersPage />
            </RequireRole>
          }
        />
        <Route
          path="/admin/accounts"
          element={
            <RequireRole roles={['ADMIN']}>
              <AdminAccountsPage />
            </RequireRole>
          }
        />
        <Route
          path="/audit"
          element={
            <RequireRole roles={['ADMIN', 'AUDITOR']}>
              <AuditPage />
            </RequireRole>
          }
        />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
