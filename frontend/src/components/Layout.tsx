import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import type { Role } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { RoleBadge } from './ui';

const LINKS: { to: string; label: string; roles: Role[] }[] = [
  { to: '/accounts', label: 'Accounts', roles: ['HOLDER', 'ADMIN', 'AUDITOR'] },
  { to: '/admin/accounts', label: 'Approvals', roles: ['ADMIN'] },
  { to: '/admin/users', label: 'Users', roles: ['ADMIN'] },
  { to: '/audit', label: 'Audit', roles: ['ADMIN', 'AUDITOR'] },
];

export function Layout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  function handleLogout() {
    logout();
    navigate('/login', { replace: true });
  }

  return (
    <div className="min-h-screen">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-3 px-4 py-3">
          <div className="flex items-center gap-6">
            <NavLink to="/accounts" className="text-lg font-bold tracking-tight text-indigo-600">
              LedgerLine
            </NavLink>
            <nav className="flex gap-1">
              {LINKS.filter((link) => user && link.roles.includes(user.role)).map((link) => (
                <NavLink
                  key={link.to}
                  to={link.to}
                  className={({ isActive }) =>
                    `rounded-md px-3 py-1.5 text-sm font-medium ${
                      isActive ? 'bg-indigo-50 text-indigo-700' : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'
                    }`
                  }
                >
                  {link.label}
                </NavLink>
              ))}
            </nav>
          </div>
          {user && (
            <div className="flex items-center gap-3 text-sm">
              <span className="text-slate-600">{user.email}</span>
              <RoleBadge role={user.role} />
              <button type="button" onClick={handleLogout} className="font-medium text-slate-500 hover:text-slate-900">
                Log out
              </button>
            </div>
          )}
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-8">
        <Outlet />
      </main>
    </div>
  );
}
