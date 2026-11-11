# LedgerLine API

Base URL: `http://localhost:8080` locally. All bodies are JSON. **All money amounts are integers in paise**
(1 rupee = 100 paise), named `...Paise`. Timestamps are ISO-8601 UTC.

> The live, always-current reference is Swagger UI at `/swagger-ui.html` (OpenAPI JSON at `/v3/api-docs`),
> generated from the controllers. This document adds the role rules, error meanings and money effects.

## Conventions

### Authentication

Log in with `POST /api/auth/login` and send the token on every other call:

```
Authorization: Bearer <token>
```

The token holds only the user id. Role and status are read from the database on every request, so a role change
or a disabled user takes effect immediately. A missing, invalid or expired token, or a DISABLED user, gets `401`.

### Error body

Every error that is not a money result uses `ApiError`:

```json
{ "status": 404, "error": "Not Found", "message": "Account not found", "timestamp": "2026-11-11T07:00:00Z" }
```

Validation errors are `400` with the field messages joined in `message`, e.g.
`"amountPaise: must be greater than 0"`. Malformed JSON, an unknown enum value, a decimal `amountPaise`
(e.g. `12.5`) or a non-numeric path id are also `400`.

### Idempotency-Key and replays

`POST /api/accounts/{id}/deposits`, `POST /api/accounts/{id}/withdrawals` and `POST /api/transfers` require the
header `Idempotency-Key`, matching `^[A-Za-z0-9_-]{8,100}$` (a UUID works). Missing or malformed: `400`.

- Keys are scoped per user. The first request with a key is processed and its result is stored.
- Repeating the **same request** (same kind, accounts and amount) with the same key returns the **original HTTP
  status and body** plus the header `Idempotent-Replayed: true`. No money moves, and this also applies to a
  request that was originally refused.
- If two requests with the same key arrive at the same time, the second waits for the first and then replays it.
- Reusing a key for a **different** request: `409` "Idempotency key reused with a different request".
- A request rejected with `400` (validation) or a thrown `404`/`409` that records nothing does not use up the key.

Clients should create one key per user action and resend the same key when retrying after a timeout or network
error.

### Refused money requests

A money request that reaches a decision is **recorded**, even when refused. The response body is then the stored
`TransactionResponse` with `status: "REJECTED"` and a `rejectReason`, and the HTTP status depends on the reason:

| `rejectReason` | HTTP | Meaning |
|---|---|---|
| `NOT_AUTHORISED` | 404 | You may not even see the account (another holder's account, or EXTERNAL as a HOLDER) |
| `NOT_AUTHORISED` | 403 | You can see the account but your role may not do this (e.g. AUDITOR, ADMIN withdrawing) |
| `ACCOUNT_NOT_ACTIVE` | 409 | The account is PENDING or FROZEN |
| `DESTINATION_UNAVAILABLE` | 404 | Transfer destination is missing, EXTERNAL, PENDING or FROZEN; all look identical and `toAccountId` is `null` |
| `INSUFFICIENT_FUNDS` | 422 | The source customer account's balance is lower than the amount |

A refused request writes 1 `transactions` row (REJECTED), no ledger entries, and changes no balance.

### Rate limits: 429 and Retry-After

Fixed windows counted in Redis (details in [ROLES.md](ROLES.md#rate-limits)). Over the limit the response is:

```
HTTP/1.1 429
Retry-After: 37
```
```json
{ "status": 429, "error": "Too Many Requests", "message": "Too many requests, try again in 37 seconds", "timestamp": "..." }
```

A throttled request never reaches the application: nothing is recorded and no money moves. If Redis is down,
requests are allowed.

---

## Auth

### POST /api/auth/register

- **Who:** public (no token).
- **Request:**
  ```json
  { "email": "asha@example.com", "fullName": "Asha Rao", "password": "s3cure-pass" }
  ```
  Email is trimmed and lowercased. Password 8–72 characters. Any `role` field is ignored: the user is always a HOLDER.
- **Response `201`:**
  ```json
  { "id": 12, "email": "asha@example.com", "fullName": "Asha Rao", "role": "HOLDER" }
  ```
- **Errors:** `400` validation, `409` email already registered, `429` register rate limit.
- **Effect on money:** none. Writes audit `USER_REGISTERED`.

### POST /api/auth/login

- **Who:** public.
- **Request:**
  ```json
  { "email": "asha@example.com", "password": "s3cure-pass" }
  ```
- **Response `200`:**
  ```json
  {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "expiresAt": "2026-11-11T07:30:00Z",
    "user": { "id": 12, "email": "asha@example.com", "fullName": "Asha Rao", "role": "HOLDER" }
  }
  ```
- **Errors:** `400` validation; `401` "Invalid email or password" (the same for an unknown email, a wrong password
  and a disabled user); `429` per-IP or per-email login limit.
- **Effect on money:** none. Writes audit `LOGIN_SUCCESS` or `LOGIN_FAILED`.

### GET /api/auth/me

- **Who:** any logged-in user.
- **Response `200`:** `{ "id": 12, "email": "asha@example.com", "fullName": "Asha Rao", "role": "HOLDER" }`
- **Errors:** `401`.
- **Effect on money:** none.

---

## Admin: users

All require **ADMIN**. Other roles get `403`.

### GET /api/admin/users

- **Response `200`:**
  ```json
  [ { "id": 1, "email": "admin@example.com", "fullName": "Administrator", "role": "ADMIN",
      "status": "ACTIVE", "createdAt": "2026-11-11T06:15:59Z" } ]
  ```
  No password hashes.
- **Errors:** `401`, `403`.
- **Effect on money:** none. Writes audit `ADMIN_USERS_LISTED`.

### POST /api/admin/users

- **Request:**
  ```json
  { "email": "ravi@example.com", "fullName": "Ravi Iyer", "password": "another-pass", "role": "AUDITOR" }
  ```
  `role` is `ADMIN`, `HOLDER` or `AUDITOR`.
- **Response `201`:** the created user (same shape as the list items).
- **Errors:** `400` validation or unknown role, `401`, `403`, `409` email already registered.
- **Effect on money:** none. Writes audit `ADMIN_USER_CREATED`.

### PATCH /api/admin/users/{id}/status

- **Request:** `{ "status": "DISABLED" }` (or `"ACTIVE"`).
- **Response `200`:** the updated user. A disabled user's existing tokens stop working on their next request.
- **Errors:** `400` "Admins cannot disable their own account" or validation, `401`, `403`, `404` "User not found".
- **Effect on money:** none. Writes audit `ADMIN_USER_STATUS_CHANGED` with `from`/`to`.

---

## Accounts

`AccountResponse`:

```json
{ "id": 6, "name": "Holder One Savings", "type": "CUSTOMER", "status": "ACTIVE", "balancePaise": 4750000,
  "ownerId": 7, "ownerEmail": "holder1@ledgerline.demo", "createdAt": "2026-11-11T07:00:00Z" }
```

The EXTERNAL account (`"type": "EXTERNAL"`, `ownerId`/`ownerEmail` null) represents money entering and leaving
the system. Its balance is negative when more has been deposited than withdrawn.

### POST /api/accounts

- **Who:** HOLDER only.
- **Request:** `{ "name": "Savings" }` (1–60 characters).
- **Response `201`:** the new account, owned by the caller, `status: "PENDING"`, `balancePaise: 0`.
- **Errors:** `400` validation, `401`, `403` not a HOLDER, `409` "A holder may have at most 5 accounts".
- **Effect on money:** none. Writes audit `ACCOUNT_REQUESTED`.

### GET /api/accounts

- **Who:** any logged-in user. HOLDER sees only their own accounts; ADMIN and AUDITOR see all, including EXTERNAL.
- **Response `200`:** `AccountResponse[]`, by id.
- **Errors:** `401`.
- **Effect on money:** none.

### GET /api/accounts/{id}

- **Who:** HOLDER (own accounts), ADMIN, AUDITOR.
- **Response `200`:** `AccountResponse`.
- **Errors:** `400` non-numeric id, `401`, `404` "Account not found" (also for another holder's account, so its
  existence is not revealed).
- **Effect on money:** none.

---

## Admin: accounts

All require **ADMIN**. Other roles get `403`.

### GET /api/admin/accounts?status=PENDING

- **Query:** `status` = `PENDING` (default), `ACTIVE` or `FROZEN`.
- **Response `200`:** `AccountResponse[]` of CUSTOMER accounts with that status.
- **Errors:** `400` unknown status, `401`, `403`.
- **Effect on money:** none.

### PATCH /api/admin/accounts/{id}/status

- **Request:** `{ "status": "ACTIVE" }`
- **Allowed:** `PENDING → ACTIVE` (approve), `ACTIVE → FROZEN`, `FROZEN → ACTIVE`.
- **Response `200`:** the updated `AccountResponse`.
- **Errors:** `400` "Invalid status transition from X to Y", `401`, `403` not ADMIN or the EXTERNAL account,
  `404` "Account not found".
- **Effect on money:** none directly; a FROZEN account refuses all money movements. Takes a row lock on the
  account, so a status change and a money movement on the same account never interleave. Writes audit
  `ACCOUNT_APPROVED`, `ACCOUNT_FROZEN` or `ACCOUNT_UNFROZEN` with `from`/`to`.

---

## Money

All three require the `Idempotency-Key` header. `amountPaise` must be an integer from 1 to 100,000,000,000.

`TransactionResponse`:

```json
{ "transactionId": 14, "kind": "TRANSFER", "status": "POSTED", "amountPaise": 250000,
  "fromAccountId": 6, "toAccountId": 7, "rejectReason": null, "createdAt": "2026-11-11T07:13:05Z" }
```

### POST /api/accounts/{id}/deposits

- **Who:** HOLDER into their own ACTIVE account; ADMIN into any ACTIVE customer account. AUDITOR: refused (403).
- **Request:** `{ "amountPaise": 250050 }` with header `Idempotency-Key: 2f1c...`
- **Response `201`:** `TransactionResponse` with `kind: "DEPOSIT"`, `status: "POSTED"`, `fromAccountId` = EXTERNAL id,
  `toAccountId` = `{id}`.
- **Errors:** `400` validation or missing/bad key; `401`; `403` REJECTED `NOT_AUTHORISED` (AUDITOR, or ADMIN on
  EXTERNAL); `404` REJECTED `NOT_AUTHORISED` (HOLDER on someone else's account) or `ApiError` "Account not found"
  (id does not exist, nothing recorded); `409` REJECTED `ACCOUNT_NOT_ACTIVE` or `ApiError` key reused for a
  different request; `429`.
- **Effect on money:** credits the account and debits EXTERNAL by `amountPaise`: writes 1 transaction (POSTED) +
  2 ledger entries. Refused: 1 REJECTED transaction, no ledger entries. Replay: none.

### POST /api/accounts/{id}/withdrawals

- **Who:** HOLDER only, from their own ACTIVE account. ADMIN and AUDITOR: refused (403).
- **Request:** `{ "amountPaise": 100000 }` with `Idempotency-Key`.
- **Response `201`:** `TransactionResponse` with `kind: "WITHDRAWAL"`, `fromAccountId` = `{id}`, `toAccountId` = EXTERNAL id.
- **Errors:** as for deposits, plus `422` REJECTED `INSUFFICIENT_FUNDS`.
- **Effect on money:** debits the account and credits EXTERNAL by `amountPaise`: writes 1 transaction (POSTED) +
  2 ledger entries. Refused: 1 REJECTED transaction, no ledger entries. Replay: none.

### POST /api/transfers

- **Who:** HOLDER only, from their own ACTIVE account into any ACTIVE customer account (including other holders').
  ADMIN and AUDITOR: refused (403).
- **Request:**
  ```json
  { "fromAccountId": 6, "toAccountId": 7, "amountPaise": 250000 }
  ```
  with `Idempotency-Key`.
- **Response `201`:** `TransactionResponse` with `kind: "TRANSFER"`, `status: "POSTED"`.
- **Errors:** `400` validation, missing/bad key, or "fromAccountId and toAccountId must be different"; `401`;
  `403` REJECTED `NOT_AUTHORISED` (ADMIN, AUDITOR); `404` REJECTED `NOT_AUTHORISED` (source is not yours),
  REJECTED `DESTINATION_UNAVAILABLE` (destination missing, EXTERNAL, PENDING or FROZEN; `toAccountId` is `null`),
  or `ApiError` "Account not found" (source id does not exist, nothing recorded); `409` REJECTED
  `ACCOUNT_NOT_ACTIVE` (source not active) or `ApiError` key reused; `422` REJECTED `INSUFFICIENT_FUNDS`; `429`.
- **Effect on money:** debits the source and credits the destination by `amountPaise`: writes 1 transaction
  (POSTED) + 2 ledger entries. Refused: 1 REJECTED transaction, no ledger entries. Replay: none.

### GET /api/accounts/{id}/statement

- **Who:** HOLDER (own accounts), ADMIN, AUDITOR.
- **Response `200`:** the current balance and up to 500 lines, newest first. Posted movements come from the ledger
  entries; refused requests appear as `REFUSED` lines.
  ```json
  {
    "accountId": 6,
    "balancePaise": 4750000,
    "lines": [
      { "createdAt": "2026-11-11T07:13:05Z", "transactionId": 14, "kind": "TRANSFER", "direction": "DEBIT",
        "amountPaise": 250000, "balanceAfterPaise": 4750000, "counterpartyAccountId": 7,
        "initiatedByEmail": "holder1@ledgerline.demo", "status": "POSTED", "rejectReason": null },
      { "createdAt": "2026-11-11T07:12:40Z", "transactionId": 13, "kind": "WITHDRAWAL", "direction": "REFUSED",
        "amountPaise": 100000, "balanceAfterPaise": 5000000, "counterpartyAccountId": 1,
        "initiatedByEmail": "another user", "status": "REJECTED", "rejectReason": "NOT_AUTHORISED" }
    ]
  }
  ```
  `direction` is `DEBIT`, `CREDIT` or `REFUSED`. For a refused line, `balanceAfterPaise` is the balance seen when
  the request was refused, shown only on the account it belongs to (otherwise `null`). A HOLDER sees
  `"another user"` instead of the email of anyone else who initiated a line; ADMIN and AUDITOR see real emails.
- **Errors:** `400` non-numeric id, `401`, `404` "Account not found".
- **Effect on money:** none.

---

## Audit

Both require **ADMIN** or **AUDITOR**; HOLDER gets `403`. Read-only.

### GET /api/audit/log?limit=100

- **Query:** `limit`, default 100, clamped to 1–500.
- **Response `200`:** newest first. `details` is the stored JSON as a string.
  ```json
  [ { "id": 88, "createdAt": "2026-11-11T07:14:02Z", "actorId": 1, "actorEmail": "admin@example.com",
      "action": "ACCOUNT_APPROVED", "targetType": "ACCOUNT", "targetId": 6,
      "details": "{\"from\":\"PENDING\",\"to\":\"ACTIVE\"}" } ]
  ```
  `actorId`/`actorEmail` are `null` for actions with no logged-in actor (e.g. `LOGIN_FAILED`, `ADMIN_BOOTSTRAPPED`).
  The audit log covers admin and security actions, not money movements (those are in `transactions`).
- **Errors:** `401`, `403`.
- **Effect on money:** none.

### GET /api/audit/reconciliation

- **Response `200`:**
  ```json
  {
    "ledgerSumPaise": 0,
    "customerTotalPaise": 7330050,
    "externalBalancePaise": -7330050,
    "balanced": true,
    "mismatchedAccounts": [],
    "postedCount": 5,
    "rejectedCount": 9,
    "duplicateRequestCount": 1,
    "checkedAt": "2026-11-11T07:14:30Z"
  }
  ```
  `balanced` is true when all ledger entries sum to 0, customer balances equal minus the EXTERNAL balance, and no
  account's balance differs from the sum of its ledger entries (any that do are listed in `mismatchedAccounts` as
  `{ accountId, balancePaise, ledgerSumPaise }`). `duplicateRequestCount` is the number of idempotency keys that
  were replayed at least once. All figures come from one consistent database snapshot; no locks are taken.
- **Errors:** `401`, `403`.
- **Effect on money:** none.

### GET /api/audit/chain

Every ledger entry written since migration V4 carries `prev_hash` and `entry_hash`, forming one hash chain per
account: `entry_hash = SHA-256 hex of "prev_hash|transaction_id|account_id|amount|balance_after"`, and `prev_hash`
is the account's previous entry's `entry_hash` (`"GENESIS"` for its first). Entries are chained while the account
row is locked, so a chain cannot fork. Changing, inserting or deleting any entry, even directly in the database by
the table owner, makes this check fail.

- **Who:** ADMIN, AUDITOR.
- **Response `200`:**
  ```json
  {
    "intact": false,
    "accountsChecked": 3,
    "entriesChecked": 18,
    "unchainedLegacyEntries": 0,
    "breaks": [
      { "accountId": 3, "entryId": 7, "problem": "HASH_MISMATCH",
        "expectedHash": "9f2c…", "storedHash": "41ab…" }
    ],
    "checkedAt": "2026-11-11T08:06:00Z"
  }
  ```
  `problem` is `HASH_MISMATCH` (the entry's data no longer matches its hash), `PREV_HASH_MISMATCH` (the link to
  the previous entry is wrong, e.g. an entry was removed or inserted) or `MISSING_HASH`. Entries written before V4
  (no hashes, before an account's first hashed entry) are counted in `unchainedLegacyEntries`, not as breaks.
  One consistent snapshot, no locks.
- **Errors:** `401`, `403`.
- **Effect on money:** none.

---

## Anomaly flags

**Rule-based anomaly flags (no ML/LLM).** After a money request is recorded and committed, fixed rules are
evaluated on a background thread in a separate transaction and may insert flags. The machine suggests; a person
decides. Flagging never blocks or rolls back a money movement, and nothing in this feature freezes an account or
moves money. Flags and reviews are append-only (triggers forbid UPDATE, DELETE and TRUNCATE).

| Rule | Severity | Raised when |
|---|---|---|
| `LARGE_AMOUNT` | HIGH | A POSTED amount is ≥ 5,000,000 paise (₹50,000) |
| `FOREIGN_PROBE` | HIGH | A request was REJECTED as `NOT_AUTHORISED`: someone tried to move money that isn't theirs |
| `REPEATED_REFUSALS` | MEDIUM | ≥ 3 REJECTED withdrawals/transfers from the same account within 10 minutes |
| `RAPID_DEBITS` | MEDIUM | ≥ 5 POSTED withdrawals/transfers from one account within 60 seconds |
| `NEAR_DRAIN` | LOW | A POSTED debit ≥ 1,000,000 paise left less than 5% of the account's prior balance |

Thresholds are configurable under `ledgerline.anomaly.*` in `application.yml`. Each rule flags a transaction at
most once (`UNIQUE (transaction_id, rule)`). The flagged account is the deposit's target, otherwise the source.

`FlagResponse`:

```json
{ "id": 4, "transactionId": 12, "accountId": 3, "rule": "NEAR_DRAIN", "severity": "LOW",
  "message": "WITHDRAWAL of ₹22,000.00 left ₹500.00 of a prior balance of ₹22,500.00 (under 5%)",
  "createdAt": "2026-11-11T08:06:00Z",
  "review": { "decision": "DISMISSED", "note": "Expected: holder emptied their own account.",
              "reviewerId": 1, "reviewerEmail": "admin@example.com", "reviewedAt": "2026-11-11T08:07:00Z" } }
```

`review` is `null` while the flag is open.

### GET /api/audit/flags?status=OPEN

- **Who:** ADMIN, AUDITOR.
- **Query:** `status` = `OPEN` (not reviewed), `REVIEWED`, or omitted for all.
- **Response `200`:** `FlagResponse[]`, newest first, at most 500.
- **Errors:** `400` unknown status, `401`, `403`.
- **Effect on money:** none.

### POST /api/admin/flags/{id}/review

- **Who:** ADMIN only.
- **Request:**
  ```json
  { "decision": "CONFIRMED", "note": "Probe from an unknown holder; freezing separately." }
  ```
  `decision` is `DISMISSED` or `CONFIRMED`; `note` is required, at most 500 characters.
- **Response `200`:** the `FlagResponse` including its review.
- **Errors:** `400` validation, `401`, `403` not ADMIN, `404` "Flag not found", `409` "Flag has already been
  reviewed" (each flag can be reviewed once).
- **Effect on money:** none. `CONFIRMED` only records the decision: it does not freeze the account or move money.
  To act, freeze the account separately with `PATCH /api/admin/accounts/{id}/status`. Writes audit `FLAG_REVIEWED`.

---

## Infrastructure (public)

| Method | Path | Notes |
|---|---|---|
| GET | `/actuator/health` | Overall status (`{"status":"UP"}`), no details |
| GET | `/actuator/health/liveness` | `UP` while the process runs |
| GET | `/actuator/health/readiness` | `503` when Postgres or Redis is unreachable |
| GET | `/swagger-ui.html`, `/v3/api-docs` | API docs, unless `SWAGGER_ENABLED=false` |

These paths are not rate limited. Effect on money: none.
