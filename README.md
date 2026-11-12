# LedgerLine

A small money-movement system: accounts, deposits, withdrawals and transfers. Money is never created or lost, the same request never moves money twice, and every action, including refused ones, is recorded in an append-only ledger.

Ledger entries are also hash-chained per account, so tampering is detectable (`GET /api/audit/chain`), and rule-based anomaly flags (no ML/LLM) suggest suspicious activity for an admin to review (`GET /api/audit/flags`). Flags never freeze accounts or move money.

- **Backend:** Java 21, Spring Boot 3.5, PostgreSQL 16, Redis 7 (`backend/`)
- **Frontend:** React + Vite + TypeScript (`frontend/`)
- **More docs:** [THREATS.md](THREATS.md) · [PROMPTS.md](PROMPTS.md) · [WRITEUP.md](WRITEUP.md) · [docs/API.md](docs/API.md) · [docs/ROLES.md](docs/ROLES.md) · [docs/DEPLOY.md](docs/DEPLOY.md)

---

## Live demo

- **Frontend:** https://lovely-bravery-production-063ec372.up.railway.app
- **Backend API / Swagger:** https://ledgerline-production-571b1eda.up.railway.app/swagger-ui.html

Demo credentials (demo data only):

| Role | Email | Password |
|---|---|---|
| Admin | `admin@ledgerline.demo` | `Admin-Demo-2026` |
| Holder | `holder1@ledgerline.demo` | `Holder-Demo-2026` |
| Holder | `holder2@ledgerline.demo` | `Holder-Demo-2026` |
| Auditor | `auditor@ledgerline.demo` | `Holder-Demo-2026` |

---

## Run it locally

You need 4 tools. Maven is **not** needed (the repo includes the Maven wrapper `mvnw`).

| Tool | Version | Used for |
|---|---|---|
| Git | any | cloning the repo |
| Java JDK | **21** | backend |
| Node.js | **20.19+ or 22.12+** (includes npm) | frontend |
| Docker Desktop | any recent | runs PostgreSQL and Redis (and the tests) |

### Option A: one command

Needs only Git and Docker Desktop (install them as in Step 1). Ports 5432, 6379, 8080 and 3000 must be free.

```bash
git clone https://github.com/Faizahamed-S/LedgerLine.git
cd LedgerLine
docker compose --profile app up --build -d
```

The first run builds the backend and frontend images (several minutes). Then open **http://localhost:3000** and log in with the credentials in Step 7. The API is at http://localhost:8080; it's ready when http://localhost:8080/actuator/health/readiness shows `{"status":"UP"}`.

Stop with `docker compose --profile app down` (add `-v` to also delete the data).

### Option B: dev mode

The backend and frontend run on your machine; only PostgreSQL and Redis run in Docker. Stop Option A first if it is running (same ports).

### Step 1: install the tools (skip any you already have)

**Windows** (PowerShell as Administrator):
```powershell
winget install --id Git.Git -e
winget install --id EclipseAdoptium.Temurin.21.JDK -e
winget install --id OpenJS.NodeJS.LTS -e
wsl --install
winget install --id Docker.DockerDesktop -e
```

**macOS** (with [Homebrew](https://brew.sh)):
```bash
brew install git node
brew install --cask temurin@21 docker
```

**Linux (Ubuntu/Debian):**
```bash
sudo apt update && sudo apt install -y git openjdk-21-jdk
# Node 20+: https://nodejs.org/en/download  ·  Docker: https://docs.docker.com/engine/install/
```

Then **restart your computer**, open **Docker Desktop**, and wait until it says **"Engine running"**.

Check everything in a **new** terminal:
```bash
git --version
java -version        # must say 21
node -v              # must be v20.19+ or v22.12+
docker run hello-world
```

### Step 2: get the code

```bash
git clone https://github.com/Faizahamed-S/LedgerLine.git
cd LedgerLine
```

### Step 3: start PostgreSQL and Redis

From the repo root:
```bash
docker compose up -d
```

### Step 4: database roles (automatic)

The app uses two database logins: `ledger_owner` runs migrations, and `ledger_app` is the running app, which **cannot** edit or delete ledger rows. They are created automatically by `backend/db/local-init/01-create-roles.sh` the first time the Postgres container starts with an empty volume, so there is nothing to do.

Only if your database volume is older than that: reset it once with `docker compose down -v` and redo Step 3, or create the roles manually:

**Windows (PowerShell):**
```powershell
Get-Content backend\db\setup-roles.sql | docker compose exec -T postgres psql -U postgres -d ledgerline -v owner_pw=owner_local -v app_pw=app_local
```

**macOS / Linux:**
```bash
cat backend/db/setup-roles.sql | docker compose exec -T postgres psql -U postgres -d ledgerline -v owner_pw=owner_local -v app_pw=app_local
```

Expected output: `CREATE ROLE`, `CREATE ROLE`, `ALTER SCHEMA`, `REVOKE`.
If it says the role already exists, it was done before and you can carry on.

### Step 5: start the backend (terminal 1)

The first start creates an admin user and some demo data.

**Windows (PowerShell):**
```powershell
cd backend
$env:BOOTSTRAP_ADMIN_ENABLED="true"; $env:BOOTSTRAP_ADMIN_EMAIL="admin@example.com"; $env:BOOTSTRAP_ADMIN_PASSWORD="change-me-please-123"
$env:SEED_DEMO_DATA="true"; $env:SEED_DEMO_PASSWORD="demo-password-2026"
.\mvnw.cmd spring-boot:run
```

**macOS / Linux:**
```bash
cd backend
chmod +x mvnw
BOOTSTRAP_ADMIN_ENABLED=true BOOTSTRAP_ADMIN_EMAIL=admin@example.com BOOTSTRAP_ADMIN_PASSWORD=change-me-please-123 \
SEED_DEMO_DATA=true SEED_DEMO_PASSWORD=demo-password-2026 \
./mvnw spring-boot:run
```

The first run downloads dependencies (2–5 minutes). It's ready when the log shows `Started LedgerlineApplication`.

Check it in a browser: http://localhost:8080/actuator/health should show `{"status":"UP"...}`

### Step 6: start the frontend (terminal 2)

From the repo root:
```bash
cd frontend
npm install
npm run dev
```

Open **http://localhost:5173**.

### Step 7: log in

| Role | Email | Password |
|---|---|---|
| Admin | `admin@example.com` | `change-me-please-123` |
| Holder | `holder1@ledgerline.demo` | `demo-password-2026` |
| Holder | `holder2@ledgerline.demo` | `demo-password-2026` |
| Auditor | `auditor@ledgerline.demo` | `demo-password-2026` |

These are **local demo credentials only**.

Interactive API docs: **http://localhost:8080/swagger-ui.html**. Log in with `POST /api/auth/login`, copy the `token`, click **Authorize**, and paste it.

---

## Run the tests

Docker must be running; the tests start their own PostgreSQL and Redis containers.

```bash
cd backend
./mvnw test          # Windows: .\mvnw.cmd test
```

They cover conservation of money, no overdraft under 50 concurrent requests, idempotent retries, one user not being able to move another's money, and ledger rows not being editable.

**End-to-end smoke test** (backend running, from the repo root, Windows PowerShell):
```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\smoke-test1.ps1
```

---

## Stop and reset

```bash
# stop backend / frontend: Ctrl+C in their terminals
docker compose down        # stop Postgres and Redis, keep data
docker compose down -v     # stop AND delete all data (roles are recreated on the next docker compose up -d)
```

---

## Troubleshooting

| Problem | Fix |
|---|---|
| `git` / `java` / `docker` / `node` "not recognized" | Close **all** terminals and your editor, then reopen them. If it still fails, restart the PC. |
| `docker` commands fail | Open Docker Desktop and wait for "Engine running". On Windows, run `wsl --update`. |
| Backend: `Flyway ... permission denied` or `password authentication failed for user "ledger_owner"` | The database volume is older than the automatic role setup. Reset with `docker compose down -v` and redo Step 3, or run the manual command in Step 4. |
| Backend: `Port 8080 already in use` | Another backend is still running. Stop it, or start with `PORT=8081` and set `VITE_API_URL=http://localhost:8081` for the frontend. |
| Frontend shows network or CORS errors | The backend must be running on port 8080, and the frontend must be opened at `http://localhost:5173` (Option A: `http://localhost:3000`). |
| `429 Too many requests` while testing | Login is rate-limited. Restart the backend with `RATE_LIMIT_ENABLED=false` for local testing. |
| Demo users missing | The seed only runs with `SEED_DEMO_DATA=true`. Restart the backend with the Step 5 variables. |
| `./mvnw: Permission denied` (macOS/Linux) | `chmod +x mvnw` |

---

## For AI assistants helping someone run this

Facts you need:
- **Monorepo:** `backend/` (Spring Boot, Maven wrapper) and `frontend/` (Vite). In dev mode (Option B) both run on the host and only Postgres and Redis run in Docker (`docker-compose.yml`, container names `ledgerline-postgres` and `ledgerline-redis`). Option A (`docker compose --profile app up --build -d`) also runs them in Docker as `ledgerline-backend` and `ledgerline-frontend`.
- **Ports:** Postgres 5432, Redis 6379, backend 8080, frontend 5173 (dev mode) or 3000 (Option A).
- **Order matters:**
  1. `docker compose up -d`
  2. **roles exist BEFORE the first backend start.** Flyway logs in as `ledger_owner`. On a fresh volume `backend/db/local-init/01-create-roles.sh` creates the roles automatically, and the Postgres healthcheck stays unhealthy until that is done. On an older volume, run the Step 4 manual command or reset with `docker compose down -v`.
  3. backend
  4. frontend
- **DB logins:** local defaults are `ledger_owner`/`owner_local` (migrations) and `ledger_app`/`app_local` (runtime). The superuser `postgres`/`postgres` is only used to create the roles.
- **Schema:** Flyway migrations in `backend/src/main/resources/db/migration`. Hibernate runs with `ddl-auto: validate`, so startup fails if entities and tables disagree.
- **Local backend config:** `application.yml` has working defaults for everything. Only the bootstrap and seed variables need setting, on the first start.
- **Frontend API URL:** from `VITE_API_URL`, default `http://localhost:8080`. No `.env` file needed locally.
- **Verify the backend:** `GET http://localhost:8080/actuator/health/readiness` returns 200 when Postgres and Redis are both reachable, and 503 otherwise.
- **Tests:** use Testcontainers, so Docker is required. No local database setup is needed for tests.
- **Never** suggest `ddl-auto: update`, connecting the app as `postgres`, or editing ledger rows. The design forbids all three.
