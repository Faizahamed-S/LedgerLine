import { Link } from 'react-router-dom';
import { Card } from '../components/ui';

export function NotFoundPage() {
  return (
    <Card>
      <h1 className="text-lg font-semibold text-slate-900">Page not found</h1>
      <p className="mt-1 text-sm text-slate-600">
        <Link to="/accounts" className="text-indigo-600 hover:underline">
          Go to accounts
        </Link>
      </p>
    </Card>
  );
}
