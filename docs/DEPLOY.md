# Deploying LedgerLine to Railway

This guide deploys LedgerLine as **four Railway services** in one project:

| Service | What it is | Built from |
|---|---|---|
| **Postgres** | Railway's PostgreSQL database | Railway template |
| **Redis** | Railway's Redis (rate-limit counters) | Railway template |
| **backend** | Spring Boot API | this repo, folder `backend/` (`backend/Dockerfile`) |
| **frontend** | React app served by nginx | this repo, folder `frontend/` (`frontend/Dockerfile`) |

Both app services have a `railway.toml` in their folder, so Railway builds them with their Dockerfile and knows
their health checks. You need: a Railway account, the repo on GitHub, and **Docker Desktop** running on your
Windows machine (used once in step 2 instead of installing `psql`).

Commands below are for **Windows PowerShell**, run from the repo root.

---

## Step 1. Create the project, Postgres and Redis

1. In Railway, click **New Project → Empty Project**.
2. In the project, click **Create → Database → Add PostgreSQL**. Leave its name as **Postgres**.
3. Click **Create → Database → Add Redis**. Leave its name as **Redis**.

The names matter: the backend variables in step 3 refer to them as `${{Postgres...}}` and `${{Redis...}}`. If you
rename a service, use the new name in those references.

---

## Step 2. Create the database roles (once, before the backend's first start)

The backend never connects as the Postgres superuser. It uses two roles:

- `ledger_owner` owns the schema; Flyway uses it to create the tables.
- `ledger_app` is what the running app uses; it gets only the permissions in the migrations (no DELETE anywhere).

`backend/db/setup-roles.sql` creates both. Run it **once**, before the backend's first deploy.

**2a. Generate two strong passwords.** Paste this into PowerShell; it prints a new random password each time it
is called:

```powershell
function New-Secret([int]$bytes = 36) {
    $buffer = New-Object byte[] $bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buffer)
    ([Convert]::ToBase64String($buffer)) -replace '[^A-Za-z0-9]', ''
}
$ownerPw = New-Secret
$appPw = New-Secret
"OWNER: $ownerPw"
"APP:   $appPw"
```

These use only letters and digits, so they are safe in URLs and command lines. Save both in a password manager:
you need them again in step 3 as `DB_OWNER_PASSWORD` and `DB_APP_PASSWORD`.

**2b. Get the public database URL.** In Railway open **Postgres → Variables**, and copy the value of
`DATABASE_PUBLIC_URL` (it looks like `postgresql://postgres:...@something.proxy.rlwy.net:12345/railway`).

**2c. Run the script** through a throwaway `postgres:16` container (Docker Desktop must be running):

```powershell
$dbUrl = "<paste DATABASE_PUBLIC_URL here>"
Get-Content backend\db\setup-roles.sql | docker run --rm -i postgres:16 psql $dbUrl -v owner_pw=$ownerPw -v app_pw=$appPw
```

Expected output: `CREATE ROLE`, `CREATE ROLE`, `ALTER SCHEMA`, `REVOKE`. If you see `role "ledger_owner" already
exists`, the script has already been run on this database; don't run it again.

---

## Step 3. Backend service

1. In the project click **Create → GitHub Repo** and pick this repository.
2. Open the new service → **Settings**:
   - **Service name:** `backend`
   - **Root Directory:** `/backend`
   - **Watch Paths:** `/backend/**` (only backend changes trigger a backend redeploy)
3. Open **Variables** and add the variables below (the **Raw Editor** lets you paste them all at once).
   `${{Postgres.PGHOST}}` and friends are Railway *reference variables*: Railway fills them in, and they point at
   the private network, so database and Redis traffic never leaves Railway.

| Variable | Value |
|---|---|
| `DB_URL` | `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}` |
| `DB_APP_USERNAME` | `ledger_app` |
| `DB_APP_PASSWORD` | the APP password from step 2a |
| `DB_OWNER_USERNAME` | `ledger_owner` |
| `DB_OWNER_PASSWORD` | the OWNER password from step 2a |
| `REDIS_HOST` | `${{Redis.REDISHOST}}` |
| `REDIS_PORT` | `${{Redis.REDISPORT}}` |
| `REDIS_PASSWORD` | `${{Redis.REDISPASSWORD}}` |
| `JWT_SECRET` | a new random secret, 48+ characters (see below) |
| `FRONTEND_ORIGIN` | `https://placeholder.invalid` for now; set the real value in step 4 |
| `SWAGGER_ENABLED` | `true` |
| `BOOTSTRAP_ADMIN_ENABLED` | `true` |
| `BOOTSTRAP_ADMIN_EMAIL` | the first admin's email, e.g. `admin@yourdomain.com` |
| `BOOTSTRAP_ADMIN_PASSWORD` | a strong password, at least 12 characters |
| `SEED_DEMO_DATA` | `true` to create the demo users, otherwise `false` |
| `SEED_DEMO_PASSWORD` | password for the demo users, at least 12 characters (required when seeding) |

Generate `JWT_SECRET` with the same function from step 2a, asking for more bytes:

```powershell
New-Secret 48     # prints about 60 random letters and digits
```

The backend refuses to start if `JWT_SECRET` is shorter than 32 bytes. Everything else (rate limits, token
lifetime, ...) has sensible defaults; the README lists every variable.

4. Open **Settings → Networking → Generate Domain**. You get a URL like `https://backend-production-xxxx.up.railway.app`.
   Note it down; this is the **backend URL**.
5. Deploy (Railway usually starts automatically when variables change). The build runs `backend/Dockerfile`; the
   tests are skipped in the build because they need Docker, which Railway's builder does not provide. Run them
   locally with `.\mvnw.cmd test` before pushing.
6. Railway waits for `/actuator/health/readiness` to return 200 (up to 120 s) before switching traffic. Readiness
   checks Postgres and Redis, so a green deploy means both are reachable.

In the deploy logs you should see Flyway apply migrations V1–V3, `Bootstrap admin created`, and (if seeding)
`Demo seed created`.

---

## Step 4. Frontend service

1. **Create → GitHub Repo**, the same repository again.
2. **Settings**:
   - **Service name:** `frontend`
   - **Root Directory:** `/frontend`
   - **Watch Paths:** `/frontend/**`
3. **Variables:**

| Variable | Value |
|---|---|
| `VITE_API_URL` | the backend URL from step 3.4, e.g. `https://backend-production-xxxx.up.railway.app` (no trailing slash) |

   Railway passes service variables to the Docker build as build arguments, and `frontend/Dockerfile` declares
   `ARG VITE_API_URL`, so the value is baked into the JavaScript bundle at build time.
4. **Settings → Networking → Generate Domain.** This is the **frontend URL**, e.g.
   `https://frontend-production-yyyy.up.railway.app`.
5. Go back to the **backend** service → **Variables** and set `FRONTEND_ORIGIN` to the frontend URL **exactly**:
   `https://` included, no trailing slash, no path. Save; the backend redeploys. CORS only allows this one origin.

---

## Step 5. Turn off the bootstrap and the seed

After the first successful backend deploy (admin and demo data exist):

1. Backend → **Variables**: set `BOOTSTRAP_ADMIN_ENABLED=false` and `SEED_DEMO_DATA=false`.
2. You can now delete `BOOTSTRAP_ADMIN_PASSWORD` and `SEED_DEMO_PASSWORD` from Railway; they are only read at
   startup when the flags are on. Keep the passwords themselves in your password manager.
3. Let the backend redeploy.

Both runners also skip themselves when their data already exists, but turning them off removes the passwords
from the running service.

---

## Step 6. Verify

1. **Health:** open `https://<backend URL>/actuator/health/readiness` → `{"status":"UP"}`.
2. **Swagger:** open `https://<backend URL>/swagger-ui.html`. Call `POST /api/auth/login`, copy the `token`,
   click **Authorize**, paste it, and try `GET /api/auth/me`.
3. **Each role in the UI:** open the frontend URL in an **incognito/private window** (one per role, so sessions
   don't mix) and log in as:
   - the bootstrap admin → Accounts, Approvals, Users, Audit; the Audit page should say **Books balance**
   - `holder1@ledgerline.demo` (if seeded) → one account with a statement; try a small transfer to holder2's account
   - `auditor@ledgerline.demo` (if seeded) → all accounts and Audit, no money forms
4. **Smoke test** against the live backend (the database checks need local Docker, so skip them):

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\smoke-test1.ps1 -BaseUrl https://<backend URL> -SkipDbCheck -AdminEmail <admin email> -AdminPassword <admin password>
   ```

   Every run creates fresh test users and accounts in the live database. The login rate limit allows 5 logins per
   email per 15 minutes, so don't run it more than a few times in a row.

---

## Troubleshooting

**Backend fails at startup with Flyway `permission denied` or `password authentication failed for user "ledger_owner"`**
The roles script from step 2 was not run against this database, or `DB_OWNER_PASSWORD` / `DB_APP_PASSWORD` don't
match the passwords you passed as `owner_pw` / `app_pw`. Run step 2 (once), check the two variables, redeploy.

**Browser console shows a CORS error** (`No 'Access-Control-Allow-Origin' header`)
`FRONTEND_ORIGIN` on the backend must equal the frontend URL exactly: same `https://`, same host, no trailing
slash, no path. Fix it and let the backend redeploy.

**The frontend says "not configured with a backend URL", or calls the wrong backend**
The app reads `VITE_API_URL` at container start (written into `/config.js`) and, as a fallback, the value baked
in at build time. A production build never falls back to localhost. Open the browser console: the app logs
`LedgerLine API base URL: <url>` at startup, and `https://<frontend URL>/config.js` shows the runtime value. Make
sure `VITE_API_URL` is set on the **frontend** service, in the **same environment**, and that pending variable
changes have been applied (Railway stages variable edits until you deploy them). Then redeploy the frontend. The
build log prints `WARNING: VITE_API_URL build arg is empty` when the build did not receive it.

**Backend logs `NOAUTH Authentication required` or readiness stays DOWN because of Redis**
`REDIS_PASSWORD` is missing or wrong. Set it to `${{Redis.REDISPASSWORD}}` and redeploy. If your Redis requires a
username too, also set `REDIS_USERNAME=${{Redis.REDISUSER}}`.

**Deploy fails the health check after 120 s**
Open the deploy logs. Readiness returns 503 until both Postgres and Redis are reachable, so look for a database or
Redis connection error first (usually one of the problems above).

**`ledgerline.jwt.secret (JWT_SECRET) must be at least 32 bytes for HS256` at startup**
`JWT_SECRET` is missing or too short. Generate one with `New-Secret 48`.
