# Roles and permissions

LedgerLine has three roles: **ADMIN**, **HOLDER**, and **AUDITOR**. The role lives in the database, not in the JWT,
so a role change or a disabled account takes effect on the very next request.

All account permission decisions are made in one place: `com.ledgerline.account.AccountAccessPolicy`.

## Endpoints

"Any" means any logged-in, ACTIVE user. "Public" means no token is needed.

| Method | Path | Public | HOLDER | ADMIN | AUDITOR | Notes |
|---|---|---|---|---|---|---|
| GET | `/actuator/health/**` | ✅ | ✅ | ✅ | ✅ | Liveness/readiness only |
| GET | `/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs/**` | ✅ | ✅ | ✅ | ✅ | API docs; turned off with `SWAGGER_ENABLED=false` |
| POST | `/api/auth/register` | ✅ | — | — | — | Always creates a HOLDER; any `role` sent is ignored |
| POST | `/api/auth/login` | ✅ | — | — | — | Same 401 for unknown email, wrong password, disabled user |
| GET | `/api/auth/me` | | ✅ | ✅ | ✅ | |
| GET | `/api/admin/users` | | ❌ | ✅ | ❌ | |
| POST | `/api/admin/users` | | ❌ | ✅ | ❌ | Can create any role |
| PATCH | `/api/admin/users/{id}/status` | | ❌ | ✅ | ❌ | An admin cannot disable themselves (400) |
| POST | `/api/accounts` | | ✅ | ❌ | ❌ | Creates own CUSTOMER account, PENDING; max 5 per holder (409) |
| GET | `/api/accounts` | | ✅ own | ✅ all | ✅ all | ADMIN/AUDITOR also see the EXTERNAL account |
| GET | `/api/accounts/{id}` | | ✅ own | ✅ all | ✅ all | A HOLDER gets 404 for anything not theirs |
| GET | `/api/admin/accounts?status=` | | ❌ | ✅ | ❌ | CUSTOMER accounts with that status (default PENDING) |
| PATCH | `/api/admin/accounts/{id}/status` | | ❌ | ✅ | ❌ | PENDING→ACTIVE, ACTIVE→FROZEN, FROZEN→ACTIVE only |
| POST | `/api/accounts/{id}/deposits` | | ✅ own | ✅ any customer | ❌ | Needs `Idempotency-Key`. Account must be ACTIVE |
| POST | `/api/accounts/{id}/withdrawals` | | ✅ own | ❌ | ❌ | Needs `Idempotency-Key`. Account must be ACTIVE |
| POST | `/api/transfers` | | ✅ from own | ❌ | ❌ | Needs `Idempotency-Key`. Destination: any ACTIVE customer account |
| GET | `/api/accounts/{id}/statement` | | ✅ own | ✅ all | ✅ all | Newest first, max 500 lines, plus current balance |

❌ = 403. Missing or invalid token = 401.

For the three money endpoints, a refused request is still **recorded** as a REJECTED transaction, and the
response body is the transaction, with the HTTP status from the table below. Repeating a request with the same
`Idempotency-Key` returns the original status and body plus `Idempotent-Replayed: true`, and moves no money.
Reusing a key for a different request returns 409.

## Rate limits

Fixed windows, counted in Redis. Over the limit: **429** with a `Retry-After: <seconds>` header and
"Too many requests, try again in N seconds". Every limit can be changed with an environment variable, and
`RATE_LIMIT_ENABLED=false` turns them all off.

| Rule | Applies to | Limit | Counted per |
|---|---|---|---|
| `login-ip` | `POST /api/auth/login` | 20 / 60 s | client IP |
| `login-email` | `POST /api/auth/login` | 5 / 900 s, successful or not | email in the request |
| `register-ip` | `POST /api/auth/register` | 20 / 3600 s | client IP |
| `money` | `POST` deposits, withdrawals, transfers | 60 / 60 s | user |
| `api` | every other authenticated `/api/**` request | 300 / 60 s | user |

`/actuator/**`, `/swagger-ui/**` and `/v3/api-docs/**` are never limited. If Redis is down, requests are allowed
(fail open): the database, not the rate limiter, protects the money.

## Permission rules

### HOLDER
- Can request new accounts for themselves only.
- Can see only their own accounts.
- Can deposit to, withdraw from, and transfer out of their own accounts, but only when the account is ACTIVE.
- Can transfer into any ACTIVE customer account, including other people's.

### ADMIN
- Can see every account.
- Approves new accounts, freezes them, and unfreezes them.
- Can deposit into any ACTIVE customer account.
- Can **never** withdraw from or transfer out of a customer's account.

### AUDITOR
- Read-only. Can see every account.
- Never changes anything and never moves money.

### The EXTERNAL account
- Represents money entering or leaving the system ("External World").
- Never visible to a HOLDER.
- Nobody can change it through the API.

## How denials look

Checks run in this order, and the first failing one decides the response:

| Situation | Response |
|---|---|
| You are not allowed to see the account (another holder's account, or EXTERNAL as a HOLDER) | **404** "Account not found". We do not reveal that it exists. |
| You can see it but may not do this action (e.g. AUDITOR depositing, ADMIN withdrawing, anyone except a HOLDER acting on EXTERNAL) | **403** |
| You may do this action, but the account is not ACTIVE (PENDING or FROZEN) | **409** "Account is not active" |
| Transfer destination is missing, EXTERNAL, PENDING or FROZEN | **404** "Destination account not available", the same in every case |
| The source account does not have enough money | **422** |
| Invalid account status change (e.g. PENDING→FROZEN) | **400** "Invalid status transition from X to Y" |

For money requests the `rejectReason` in the response is one of `NOT_AUTHORISED`, `ACCOUNT_NOT_ACTIVE`,
`DESTINATION_UNAVAILABLE`, `INSUFFICIENT_FUNDS`.
