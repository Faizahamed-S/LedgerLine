# PROMPTS.md

A log of each prompt given to the AI, what it produced, what I checked, and what it got wrong.

---

## Prompt 1 — Project setup (2026-11-10)

**Goal:** Repo skeleton only. `docker-compose.yml` (Postgres 16 + Redis 7), Spring Boot 3.5.x Maven backend,
`application.yml` driven by env vars, an empty Flyway V1 migration, a locked-down `SecurityConfig`, and `.gitignore`.
No entities, controllers, or frontend.

### What was produced

- `docker-compose.yml`: `postgres:16` (db `ledgerline`, named volume `postgres-data`) and `redis:7`, both with healthchecks
- `backend/pom.xml`: Spring Boot `3.5.16`, Java 21, all requested dependencies (versions managed by the Boot parent)
- `backend/mvnw`, `backend/mvnw.cmd`, `backend/.mvn/wrapper/maven-wrapper.properties`: Maven wrapper 3.3.4 → Maven 3.9.16
- `backend/src/main/java/com/ledgerline/LedgerlineApplication.java`
- `backend/src/main/java/com/ledgerline/config/SecurityConfig.java`
- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/db/migration/V1__init.sql` (comment only)
- `.gitignore`, `.gitattributes` (forces LF on `mvnw` so it runs on Linux/macOS/CI)

### Checklist

| Check | How I checked | Result |
|---|---|---|
| Spring Boot is 3.5.x, not 4.x | `spring-boot-starter-parent` version in `pom.xml` | ✅ `3.5.16` |
| `flyway-database-postgresql` present (Flyway 10+ fails with "Unsupported Database" without it) | searched `pom.xml` | ✅ present next to `flyway-core` |
| `ddl-auto` is `validate`, not `update` | `application.yml` → `spring.jpa.hibernate.ddl-auto` | ✅ `validate` |
| No hardcoded passwords except local defaults | searched repo for `password` | ✅ only `${DB_PASSWORD:postgres}` in `application.yml` and the local dev container's `POSTGRES_PASSWORD` in `docker-compose.yml` |
| `SecurityConfig` denies everything else (not `permitAll()` on all paths) | read `SecurityConfig.java` | ✅ only `/actuator/health` + `/actuator/health/**` permitted, `anyRequest().denyAll()`; CSRF disabled; `STATELESS` sessions; HTTP Basic and form login disabled |
| `open-in-view: false`, Flyway enabled, only `health` exposed, probes on, `PORT` default 8080 | read `application.yml` | ✅ |

### What the AI got wrong / I had to catch

1. **No Maven wrapper.** The first pass generated `pom.xml` but no `mvnw`, so `./mvnw spring-boot:run` would fail
   with "command not found". Fixed by adding `mvnw`, `mvnw.cmd`, and `.mvn/wrapper/maven-wrapper.properties`.
   *Lesson:* explicitly ask for the Maven wrapper in setup prompts.
2. **`.gitignore` negation bug (self-corrected).** It first wrote `.vscode/` followed by `!.vscode/extensions.json`.
   Git can't re-include a file whose parent directory is ignored, so the exception did nothing. Changed to `.vscode/*`.

### Run verification

Status: **not yet run.** The machine that generated this had no JDK, Docker, or git on PATH. Fill in when run:

```bash
docker compose up -d
cd backend
./mvnw spring-boot:run          # Windows PowerShell: .\mvnw.cmd spring-boot:run
```

```bash
curl http://localhost:8080/actuator/health      # expect {"status":"UP",...}
curl -i http://localhost:8080/anything          # expect 401 or 403
```

(In Windows PowerShell use `curl.exe`; plain `curl` is an alias for `Invoke-WebRequest`.)

- [ ] Flyway log shows `Successfully applied 1 migration ... v1`
- [ ] `/actuator/health` → `UP`
- [ ] `/actuator/health/liveness` and `/actuator/health/readiness` → `UP`
- [ ] `/anything` → 401/403
- [ ] Committed: `Step 1: project setup - Spring Boot, Postgres, Redis, Flyway, health check`
