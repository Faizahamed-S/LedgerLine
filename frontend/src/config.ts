declare global {
  interface Window {
    /** Written by the container at start (nginx/40-ledgerline-config.sh) from the runtime VITE_API_URL. */
    __LEDGERLINE_CONFIG__?: { apiUrl?: string };
  }
}

/**
 * Backend base URL, in order: the runtime value written into /config.js when the container starts, then the value
 * baked in at build time, then (dev server only) http://localhost:8080. A production build never falls back to
 * localhost: with no URL configured, API calls fail with a clear message instead of silently going nowhere.
 */
function resolveApiUrl(): string | null {
  const runtime = window.__LEDGERLINE_CONFIG__?.apiUrl?.trim();
  if (runtime) return withoutTrailingSlash(runtime);
  const buildTime = import.meta.env.VITE_API_URL?.trim();
  if (buildTime) return withoutTrailingSlash(buildTime);
  if (import.meta.env.DEV) return 'http://localhost:8080';
  return null;
}

function withoutTrailingSlash(url: string): string {
  return url.replace(/\/+$/, '');
}

export const API_URL: string | null = resolveApiUrl();

console.info(`LedgerLine API base URL: ${API_URL ?? '(not configured)'}`);
if (!API_URL) {
  console.error(
    'LedgerLine: VITE_API_URL was not set at build time or at container start, so the app cannot reach the backend.',
  );
}
