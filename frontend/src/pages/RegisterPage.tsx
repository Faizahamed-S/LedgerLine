import { useState, type FormEvent } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { messageOf } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { Button, ErrorState, TextField } from '../components/ui';
import { AuthShell } from './LoginPage';

export function RegisterPage() {
  const { user, register } = useAuth();
  const navigate = useNavigate();

  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (user) return <Navigate to="/accounts" replace />;

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (password.length < 8) {
      setError('Password must be at least 8 characters.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await register({ email, fullName, password });
      navigate('/accounts', { replace: true });
    } catch (e) {
      setError(messageOf(e));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthShell title="Create your account">
      <form onSubmit={handleSubmit} className="space-y-4">
        <TextField label="Full name" autoComplete="name" value={fullName} onChange={(e) => setFullName(e.target.value)} required />
        <TextField label="Email" type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        <TextField
          label="Password (at least 8 characters)"
          type="password"
          autoComplete="new-password"
          minLength={8}
          maxLength={72}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
        />
        {error && <ErrorState message={error} />}
        <Button type="submit" disabled={submitting} className="w-full">
          {submitting ? 'Creating account…' : 'Register'}
        </Button>
      </form>
      <p className="mt-4 text-center text-sm text-slate-500">
        Already registered?{' '}
        <Link to="/login" className="font-medium text-indigo-600 hover:underline">
          Log in
        </Link>
      </p>
    </AuthShell>
  );
}
