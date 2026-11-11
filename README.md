# LedgerLine

## 1. What LedgerLine is

LedgerLine is a small banking ledger with a REST API and a web UI. Holders request accounts, an admin approves
them, and money moves through deposits, withdrawals and transfers. Every posted movement is recorded as one
transaction with two double-entry ledger lines that sum to zero; refused requests are recorded too. Auditors get
read-only access to every account, the audit log, and a reconciliation check that the books balance.

## 2. Tech stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.5 (Web, Security, Data JPA, Validation, Actuator, Data Redis), Maven wrapper |
| Auth | Spring Security OAuth2 resource server, HS256 JWT (Nimbus), BCrypt password hashing |
| Database | PostgreSQL 16, Flyway migrations, Hibernate (`ddl-auto: validate`) |
| Rate limiting | Redis 7, fixed-window counters via a Lua script |
| API docs | springdoc-openapi 2.8 (Swagger UI) |
| Frontend | React 18, TypeScript, Vite 7, react-router-dom 6, Tailwind CSS v4 |
| Tests | JUnit 5, Testcontainers (real Postgres and Redis), Spring Boot Test |
| Local infra | Docker Compose |

## 3. Architecture

```
  Browser
  React SPA (frontend/, Vite dev server :5173)
        |
        |  JSON over HTTP, Authorization: Bearer <JWT>
        v
  Spring Boot API (backend/, :8080)
  security filter chain -> rate limit filter -> controllers -> services
        |                                   |
        v                                   v
  PostgreSQL 16                         Redis 7
  users, accounts, transactions,        rate-limit counters only
  ledger_entries, idempotency_keys,
  audit_log (Flyway V1-V3)
```

The app connects to Postgres as `ledger_app` (least privilege, no DELETE anywhere); Flyway runs migrations as
`ledger_owner`. `transactions`, `ledger_entries` and `audit_log` are append-only, enforced by triggers.

```
LedgerLine/
├── backend/                      Spring Boot application
│   ├── db/setup-roles.sql        creates the two database roles (run once per database)
│   ├── db/local-init/            runs setup-roles.sql automatically in the local Postgres container
│   ├── src/main/java/com/ledgerline/
│   │   ├── account/              accounts and the central AccountAccessPolicy
│   │   ├── admin/                admin user and account management
│   │   ├── audit/                audit log writer, audit log + reconciliation endpoints
│   │   ├── auth/                 register, login, /me
│   │   ├── bootstrap/            first-admin bootstrap and demo seed
│   │   ├── common/               ApiError and the global exception handler
│   │   ├── config/               OpenAPI config
│   │   ├── domain/               JPA entities and enums
│   │   ├── money/                deposits, withdrawals, transfers, statements
│   │   ├── ratelimit/            Redis rate limiter and filter
│   │   ├── repository/           Spring Data repositories
│   │   └── security/             security config, JWT, current user
│   ├── src/main/resources/db/migration/   Flyway migrations V1-V3
│   └── src/test/                 integration tests (Testcontainers)
├── frontend/                     React app (see frontend/README.md)
├── docs/                         ROLES.md, API.md
├── scripts/smoke-test1.ps1       end-to-end smoke test against a running backend
└── docker-compose.yml            Postgres 16 + Redis 7 for local development
```

## 4. Roles

| Role | Can do |
|---|---|
| HOLDER | Register, request own accounts (max 5), see only own accounts, deposit to / withdraw from / transfer out of own ACTIVE accounts, transfer into any ACTIVE customer account |
| ADMIN | Manage users (create any role, enable/disable), approve / freeze / unfreeze accounts, see all accounts, deposit into any ACTIVE customer account. Never withdraws or transfers out of a customer's account |
| AUDITOR | Read-only: all accounts, statements, the audit log and reconciliation. Never moves money |

The first ADMIN can only be created by the bootstrap at startup; there is no HTTP endpoint for it.
Full endpoint-by-role table and denial rules: [docs/ROLES.md](docs/ROLES.md).

## 5. Running locally (Windows PowerShell)

The database roles (`ledger_owner`, `ledger_app`) are created automatically the first time the Postgres
container starts with an empty volume (`backend/db/local-init/01-create-roles.sh` runs
`backend/db/setup-roles.sql`). On first start the backend creates the schema (Flyway), the first admin and the
demo data.

> **If you used an older local database** (created before this automatic setup), reset it once with
> `docker compose down -v`. This deletes all local data; the next start recreates everything.

### Option A: one command (Docker only)

Prerequisite: Docker Desktop running. Run from the repo root:

```powershell
docker compose --profile app up --build -d
```

The first run builds both images and takes several minutes (Maven and npm download their dependencies). Then:

- App: http://localhost:3000
- API: http://localhost:8080 (Swagger UI: http://localhost:8080/swagger-ui.html)

The backend is ready when `curl.exe http://localhost:8080/actuator/health/readiness` returns `{"status":"UP"}`
(about 10–30 seconds after the containers start). Follow its log with `docker compose logs -f backend`.

Stop with `docker compose --profile app down` (keeps the data) or `docker compose --profile app down -v`
(also deletes the database).

### Option B: dev mode (backend and frontend on your machine)

Prerequisites: Docker Desktop, JDK 21, Node.js 20.19+ or 22.12+. Stop Option A first if it is running (it uses
the same ports).

**1. Start Postgres and Redis**

```powershell
docker compose up -d
```

**2. Start the backend** with the first-admin bootstrap and demo seed turned on:

```powershell
cd backend
$env:BOOTSTRAP_ADMIN_ENABLED = "true"
$env:BOOTSTRAP_ADMIN_EMAIL = "admin@example.com"
$env:BOOTSTRAP_ADMIN_PASSWORD = "change-me-please-123"
$env:SEED_DEMO_DATA = "true"
$env:SEED_DEMO_PASSWORD = "demo-password-2026"
.\mvnw.cmd spring-boot:run
```

Both steps are skipped automatically once an admin / the demo users exist.

**3. Start the frontend** (second terminal, from the repo root)

```powershell
cd frontend
npm install
npm run dev
```

Open http://localhost:5173.

### Local demo credentials (local only)

Both options create these users. They exist only in your local database; never use these passwords anywhere else.

| Role | Email | Password |
|---|---|---|
| ADMIN | `admin@example.com` | `change-me-please-123` |
| HOLDER | `holder1@ledgerline.demo` | `demo-password-2026` |
| HOLDER | `holder2@ledgerline.demo` | `demo-password-2026` |
| AUDITOR | `auditor@ledgerline.demo` | `demo-password-2026` |

holder1 and holder2 each have one ACTIVE account, funded by deposits and one transfer from holder1 to holder2.

### API docs and health

- Swagger UI: http://localhost:8080/swagger-ui.html (log in via `POST /api/auth/login`, then "Authorize" with the token)
- Health: http://localhost:8080/actuator/health, `/actuator/health/liveness`, `/actuator/health/readiness`

## 6. Environment variables

| Variable | Read by | Default | Meaning |
|---|---|---|---|
| `PORT` | backend | `8080` | HTTP port |
| `DB_URL` | backend | `jdbc:postgresql://localhost:5432/ledgerline` | JDBC URL, used by the app and by Flyway |
| `DB_APP_USERNAME` | backend | `ledger_app` | Role the application connects as |
| `DB_APP_PASSWORD` | backend | `app_local` | Password for `DB_APP_USERNAME` |
| `DB_OWNER_USERNAME` | backend | `ledger_owner` | Role Flyway runs migrations as |
| `DB_OWNER_PASSWORD` | backend | `owner_local` | Password for `DB_OWNER_USERNAME` |
| `REDIS_HOST` | backend | `localhost` | Redis host |
| `REDIS_PORT` | backend | `6379` | Redis port |
| `REDIS_USERNAME` | backend | empty | Redis ACL username, if the server needs one |
| `REDIS_PASSWORD` | backend | empty | Redis password; empty means no AUTH (local Docker Redis) |
| `REDIS_TIMEOUT` | backend | `1s` | Redis command timeout |
| `REDIS_CONNECT_TIMEOUT` | backend | `1s` | Redis connect timeout |
| `JWT_SECRET` | backend | dev-only placeholder | HS256 signing secret; startup fails if shorter than 32 bytes |
| `JWT_TTL_MINUTES` | backend | `30` | Token lifetime |
| `FRONTEND_ORIGIN` | backend | `http://localhost:5173` | The only origin allowed by CORS |
| `BOOTSTRAP_ADMIN_ENABLED` | backend | `false` | Create the first ADMIN at startup if no admin exists |
| `BOOTSTRAP_ADMIN_EMAIL` | backend | empty | Email of that admin (required when enabled) |
| `BOOTSTRAP_ADMIN_PASSWORD` | backend | empty | Password of that admin, at least 12 characters (required when enabled) |
| `SEED_DEMO_DATA` | backend | `false` | Create the demo users, accounts and money at startup if not present |
| `SEED_DEMO_PASSWORD` | backend | empty | Password for all demo users, at least 12 characters (required when enabled) |
| `SWAGGER_ENABLED` | backend | `true` | Serve `/swagger-ui.html` and `/v3/api-docs` |
| `RATE_LIMIT_ENABLED` | backend | `true` | Turn all rate limits on or off |
| `RATE_LIMIT_LOGIN_IP_LIMIT` | backend | `20` | Logins per client IP per window |
| `RATE_LIMIT_LOGIN_IP_WINDOW_SECONDS` | backend | `60` | Window for the rule above |
| `RATE_LIMIT_LOGIN_EMAIL_LIMIT` | backend | `5` | Login attempts per email per window |
| `RATE_LIMIT_LOGIN_EMAIL_WINDOW_SECONDS` | backend | `900` | Window for the rule above |
| `RATE_LIMIT_REGISTER_IP_LIMIT` | backend | `20` | Registrations per client IP per window |
| `RATE_LIMIT_REGISTER_IP_WINDOW_SECONDS` | backend | `3600` | Window for the rule above |
| `RATE_LIMIT_MONEY_LIMIT` | backend | `60` | Deposits + withdrawals + transfers per user per window |
| `RATE_LIMIT_MONEY_WINDOW_SECONDS` | backend | `60` | Window for the rule above |
| `RATE_LIMIT_API_LIMIT` | backend | `300` | Other authenticated `/api/**` requests per user per window |
| `RATE_LIMIT_API_WINDOW_SECONDS` | backend | `60` | Window for the rule above |
| `VITE_API_URL` | frontend (build time) | `http://localhost:8080` | Backend base URL |

The database role passwords themselves are set once by `backend/db/setup-roles.sql` (psql variables `owner_pw`
and `app_pw`) and must match `DB_OWNER_PASSWORD` / `DB_APP_PASSWORD`.

## 7. Tests

### Automated tests

```powershell
cd backend
.\mvnw.cmd test
```

Docker must be running: the tests start real `postgres:16` and `redis:7` containers with Testcontainers. The app
connects as `ledger_app` and Flyway as `ledger_owner`, as in production, so permission tests are meaningful. The
ledger is never cleaned between tests; each test creates its own users and accounts.

| Test class | What it proves |
|---|---|
| `money.ConcurrencyTest` | 50 concurrent withdrawals of 3,000 from 10,000: exactly 3 post and 47 are refused, final balance 1,000 and equal to the ledger. 100 opposing concurrent transfers finish without deadlock and conserve the total |
| `money.IdempotencyTest` | Same key twice or from 20 threads: one transaction, identical replayed responses, correct `replay_count`. Same key with a different body: 409. Keys are per user. A refused request replays as refused even after funds arrive |
| `money.AuthorisationTest` | A holder cannot withdraw or transfer from another holder's account (404, recorded), ADMIN cannot withdraw (403), AUDITOR cannot move money (403), no token is 401, `role` in register is ignored, HOLDER cannot call admin endpoints, PENDING accounts refuse money (409) |
| `money.ConservationTest` | 200 random concurrent operations from 10 threads: ledger sums to 0, every balance equals its ledger entries, every posted transaction has 2 entries summing to 0, no negative customer balance, customers = −EXTERNAL |
| `ledger.LedgerImmutabilityTest` | `ledger_app` cannot UPDATE/DELETE ledger tables (permission denied); even `ledger_owner` is stopped by the append-only triggers, including TRUNCATE |
| `ratelimit.RateLimitTest` | The 4th login for one email gets 429 with `Retry-After`; another email still works; the throttled attempt writes no audit row |
| `audit.AuditEndpointsTest` | HOLDER gets 403 on audit endpoints; AUDITOR reads the log newest first; reconciliation is balanced after real money movement |
| `bootstrap.DemoSeedTest` | The demo seed creates one login per role, moves money only through `MoneyService` (fixed idempotency keys), leaves the books balanced, and a second run changes nothing |
| `config.SwaggerTest` | `/v3/api-docs` is public and declares the `bearerAuth` scheme and the `Idempotency-Key` header |

### Smoke test

`scripts/smoke-test1.ps1` drives a running backend over HTTP: every endpoint plus the main attacks (cross-account
access, role escalation, replays, bad amounts), then checks the ledger in the local Docker database.

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\smoke-test1.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\smoke-test1.ps1 -BaseUrl https://your-backend.example.com -SkipDbCheck
```

It logs in as `admin@example.com` / `change-me-please-123` unless you pass `-AdminEmail` / `-AdminPassword`.
Each run logs the admin in once and registers twice, so with rate limits on, repeated runs hit the login-per-email
limit (5 per 15 minutes); start the backend with `RATE_LIMIT_ENABLED=false` for repeated local runs.

## 8. Demo credentials

Live demo: URL and credentials will be added after deployment.

## 9. Links

- [THREATS.md](THREATS.md)
- [PROMPTS.md](PROMPTS.md)
- [docs/API.md](docs/API.md)
- [docs/ROLES.md](docs/ROLES.md)
- [docs/DEPLOY.md](docs/DEPLOY.md): deploying to Railway
- [frontend/README.md](frontend/README.md)

## 10. Not built / unfinished

See THREATS.md "Not built".
