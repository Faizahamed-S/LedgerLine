# LedgerLine frontend

React 18 + TypeScript + Vite + Tailwind CSS v4. Talks only to the LedgerLine backend.

## Requirements

- Node.js 20.19+ or 22.12+ (required by Vite 7)
- The backend running (by default on http://localhost:8080)

## Run it

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:5173.

The backend only accepts browser requests from the origin in its `FRONTEND_ORIGIN` setting, which defaults to
`http://localhost:5173`, so keep the dev server on that port (it is pinned in `vite.config.ts`).

## Backend URL

The API base URL comes from `VITE_API_URL` and defaults to `http://localhost:8080`. To change it, copy
`.env.example` to `.env` and edit it:

```bash
VITE_API_URL=https://your-backend.example.com
```

Vite reads this at build time, so restart `npm run dev` (or rebuild) after changing it.

In the Docker image, a runtime `VITE_API_URL` environment variable also works: at container start it is written
into `/config.js` and takes precedence over the build-time value. Production builds never fall back to
`localhost`; the browser console logs `LedgerLine API base URL: <url>` at startup.

## Other scripts

- `npm run build` type-checks and builds to `dist/`
- `npm run preview` serves the built `dist/`
- `npm run typecheck` type-checks only

<!-- rebuild for Railway VITE_API_URL -->
