# THREATS.md — LedgerLine

How the money or the ledger could be broken, what stops each attack, where that lives in the code,
which test proves it, and which risks I knowingly accepted.

Status:
- **Closed**: prevented, and a test or a check shows it.
- **Accepted**: still open or only detected, with the reason I left it.

---

## The invariants

1. **Money is conserved.** Every posted transaction writes exactly two ledger entries that sum to 0.
   Deposits and withdrawals go through the special EXTERNAL account, so this holds for them too:
   - `SUM(ledger_entries.amount) = 0` at all times
   - `SUM(customer balances) = -(EXTERNAL balance)`
2. **No customer account goes below 0.** There is no overdraft.
3. **Every account's balance equals the sum of its ledger entries.**
4. **The same request never moves money twice.**
5. **The ledger is append-only.** `transactions`, `ledger_entries` and `audit_log` rows are never updated or deleted.

`ConservationTest` checks 1–3 after 200 random concurrent operations. `GET /api/audit/reconciliation`
checks them live on the deployed system.

---

## 1. Concurrency (races)

| # | Threat | What stops it | Where | Test | Status |
|---|---|---|---|---|---|
| C1 | Two withdrawals hit at the same instant; both see enough balance; account overdrawn | Each money request is one DB transaction. Accounts are locked with `SELECT ... FOR UPDATE` before the balance is read, and the check happens under that lock. The second request waits, then sees the new balance. `CHECK (balance >= 0)` in the DB is a backstop. | `MoneyService`, `AccountRepository.findByIdForUpdate`, V2 | `ConcurrencyTest`: 50 concurrent withdrawals of 3,000 from 10,000; exactly 3 succeed | Closed |
| C2 | Lost update: two requests read the same balance and both write, so one write is lost | Same row lock. The test also asserts `balance == SUM(ledger entries)`, which catches a lost update even when the balance stays non-negative. | `MoneyService` | `ConcurrencyTest`, `ConservationTest` | Closed |
| C3 | Deadlock: A→B and B→A at the same time each hold one lock and wait for the other | Accounts are always locked in ascending id order | `MoneyService` | `ConcurrencyTest`: 50 A→B + 50 B→A concurrent, finishes in under 30s, total unchanged | Closed |
| C4 | Admin freezes an account while a transfer from it is in progress | The status change locks the same row with `findByIdForUpdate`, so the two run one after the other | `AdminAccountService` | none (design only) | Closed, untested |
| C5 | A transfer fails halfway: the debit is written but the credit is lost | Debit, credit, both entries and the transaction row are all in one DB transaction. Postgres commits all of it or none of it. | `MoneyService` (`@Transactional`) | no fault-injection test | Closed by DB atomicity, untested |
| C6 | Account limit (max 5) bypassed by parallel requests | The holder's user row is locked before counting | `AccountService` | none | Closed, untested |

**I tested that the tests are real:** I replaced `findByIdForUpdate` with `findById` and ran the tests.
`ConcurrencyTest` failed (see PROMPTS.md). With the lock back, they pass.

---

## 2. Idempotency (exactly once)

| # | Threat | What stops it | Where | Test | Status |
|---|---|---|---|---|---|
| I1 | Retry after a timeout or a double-click pays twice | Every money request needs an `Idempotency-Key`. `idempotency_keys` has `UNIQUE (user_id, idempotency_key)`. The key is claimed with `INSERT ... ON CONFLICT DO NOTHING` inside the same transaction that moves the money. | `MoneyService`, V2 | `IdempotencyTest`: same key twice gives one transaction and the same body | Closed |
| I2 | The retry arrives while the first request is still running | The second `INSERT` waits on the unique index until the first commits, then sees the key and returns the stored result | `MoneyService` | `IdempotencyTest`: 20 threads, same key, one transaction, `replay_count = 19` | Closed |
| I3 | Same key reused with a different amount or account | SHA-256 `request_hash` of the request is stored; a mismatch gives 409 | `MoneyService` | `IdempotencyTest` | Closed |
| I4 | A rejected request is retried after money arrives and now succeeds, so the "same" request has two outcomes | A replay returns the stored original result, not a new decision | `MoneyService` | `IdempotencyTest` | Closed |
| I5 | "Show us which requests were duplicates" | `replay_count` and `last_replayed_at` on `idempotency_keys`; `duplicateRequestCount` in reconciliation; `Idempotent-Replayed: true` header | V2, `AuditQueryService` | `IdempotencyTest`, smoke test | Closed |
| I6 | Keys stored forever | No expiry | — | — | **Accepted.** The table grows, which is fine at demo scale. A real system would expire keys after e.g. 24h. |
| I7 | Two different users pick the same key | Keys are scoped per user | V2 | `IdempotencyTest` | Closed |

---

## 3. Authorisation (moving money that isn't yours)

| # | Threat | What stops it | Where | Test | Status |
|---|---|---|---|---|---|
| A1 | Holder withdraws or transfers from another holder's account (IDOR) | `AccountAccessPolicy` decides on the server for every request, after the account is locked. "Not your account" returns 404. | `AccountAccessPolicy`, `MoneyService` | `AuthorisationTest` | Closed |
| A2 | Privilege escalation: register with `"role":"ADMIN"` | The register DTO has no role field and always creates a HOLDER. Admins and auditors can only be created by an admin. | `AuthService` | `AuthorisationTest`, smoke test | Closed |
| A3 | Admin takes money out of a customer's account | The policy forbids ADMIN debits. Admins can only deposit. | `AccountAccessPolicy` | `AuthorisationTest` (403 plus a REJECTED row) | Closed |
| A4 | Auditor changes anything | The auditor role has no write permission anywhere (403) | `AccountAccessPolicy`, `SecurityConfig` | `AuthorisationTest` | Closed |
| A5 | Disabled user keeps using an old token | The JWT holds only the user id. The user and role are loaded from the DB on every request, so disabling takes effect immediately. | `JwtUserAuthenticationConverter` | smoke test step 33 | Closed |
| A6 | Stolen JWT | 30-minute expiry. Disabling the user kills it. | `TokenService` | — | **Accepted.** No refresh tokens or token revocation list. |
| A7 | Money moved on a PENDING or FROZEN account | The policy requires ACTIVE (409) | `AccountAccessPolicy` | `AuthorisationTest` | Closed |
| A8 | Trusting the frontend | Route guards in React are UX only. Every rule is enforced on the server. | — | all tests go through the HTTP API | Closed |
| A9 | Admin deposits create money out of nothing | A deposit is EXTERNAL → customer, recorded with the admin as initiator, visible to the auditor | `MoneyService` | — | **Accepted by design.** Deposits represent money coming in from outside. There is no maker-checker (second approval) step. |

---

## 4. Ledger tampering

| # | Threat | What stops it | Where | Test | Status |
|---|---|---|---|---|---|
| L1 | The app (or a bug in it) updates or deletes a ledger row | The app connects as `ledger_app`, which has only `SELECT, INSERT` on `transactions`, `ledger_entries` and `audit_log`. There is no DELETE grant anywhere. | V3, `setup-roles.sql` | `LedgerImmutabilityTest` | Closed |
| L2 | Someone with the owner login edits the ledger | `BEFORE UPDATE OR DELETE` row triggers and `BEFORE TRUNCATE` statement triggers raise an error | V3 | `LedgerImmutabilityTest` | Closed for normal use |
| L3 | The owner or a superuser disables the trigger and edits a ledger row | **Not prevented, but detected.** Every ledger entry stores `prev_hash` and `entry_hash` (SHA-256 of `prev_hash`, transaction, account, amount and balance_after), so each account's entries form a chain. The account row lock serialises postings, so the chain can't fork. `GET /api/audit/chain` recomputes every hash and reports the exact entry that was changed. | V4, `MoneyService`, `/api/audit/chain` | `HashChainTest`: disables the trigger as owner, edits an amount, and the chain check reports a break at exactly that entry | **Detected.** Still open: someone who edits a row *and* recomputes every later hash in that account's chain would not be caught. Closing that needs the latest hashes anchored outside the database (e.g. published periodically), which is not built. The owner password is treated as an admin secret. |
| L3b | Entries created before V4 have no hash | They're reported as `unchainedLegacyEntries`, not as breaks | `/api/audit/chain` | — | Accepted. A fresh database (Railway) has none. |
| L4 | The app is compromised and sets `accounts.balance` directly (it has UPDATE on that column) | Not prevented. **Detected:** reconciliation compares every balance to the sum of its entries. | `GET /api/audit/reconciliation` | `AuditEndpointsTest` | **Accepted, detected.** Fixing it would mean deriving balances from entries on every read. |
| L5 | Hibernate silently updates a ledger row | `@Immutable` on the ledger entities, no setters, and the DB grants as above | domain | — | Closed |
| L6 | Migrations edited after they ran | Flyway checksums fail at startup | Flyway | — | Closed |

---

## 5. Amounts and arithmetic

| # | Threat | What stops it | Status |
|---|---|---|---|
| M1 | Floating-point rounding creates or loses paise | Money is `BIGINT` paise / Java `long` everywhere. Jackson rejects `12.5` (no float→int coercion). The UI parses rupees as a string, never `parseFloat * 100`. | Closed (smoke test 25c) |
| M2 | Overflow with huge amounts | Max 100,000,000,000 paise per request, and `Math.addExact` / `subtractExact` | Closed |
| M3 | Zero or negative amount moves money backwards | Bean validation (400) plus DB `CHECK (amount > 0)` | Closed (smoke test 25a/b) |
| M4 | Transfer to self | 400, plus DB `CHECK (from_account_id <> to_account_id)` | Closed |
| M5 | Transfer to EXTERNAL, a frozen account or a missing account | The same 404 "Destination account not available" for all of them | Closed (smoke test 22/23) |

---

## 6. Authentication and abuse

| # | Threat | What stops it | Status |
|---|---|---|---|
| S1 | Password brute force | Redis rate limit: 20 logins/min per IP and 5 per 15 min per email. The same error for a wrong email or a wrong password. A dummy BCrypt check when the email doesn't exist (timing). | Closed (`RateLimitTest`) |
| S2 | Redis down | Rate limiting **fails open** with a WARN log; readiness reports 503 | **Accepted.** Money integrity depends on Postgres, not Redis. Failing closed would take the whole app down with Redis. |
| S3 | Spoofing the client IP with `X-Forwarded-For` | `server.forward-headers-strategy: native`: Tomcat only trusts the header from internal proxy addresses | Closed (not tested) |
| S4 | Taking over the first admin | No HTTP endpoint creates admins without an admin. Bootstrap runs only with env vars set and only if no admin exists. | Closed |
| S5 | Secrets in the repo | All secrets come from env vars. The only fixed passwords are test-only ones in `src/test`. Startup fails if the JWT secret is under 32 bytes. | Closed |
| S6 | Another website calls the API from a user's browser (CORS/CSRF) | CORS allows one origin. Auth is a bearer header, not a cookie, so there's no CSRF. | Closed |
| S7 | XSS steals the token | React escapes output. The token is in `sessionStorage`. | **Accepted.** No Content-Security-Policy header; an XSS bug could still read sessionStorage. |
| S8 | Account and email enumeration | — | **Accepted.** Account ids are sequential, so existence is guessable anyway. Refused attempts on real accounts are recorded (so the victim sees them), which makes them distinguishable from missing ids. Register returns 409 for an existing email. |

---

## 7. Privacy and records

| # | Threat | What stops it | Status |
|---|---|---|---|
| P1 | A holder sees who probed their account | Holders see "another user"; admin and auditor see the real email | Closed (`AuthorisationTest`) |
| P2 | A refused transfer shows the sender's balance on the recipient's statement | `balanceAfter` is null on the recipient's line | Closed |
| P3 | Password hashes leaked by the API | Responses are DTO records and never include hashes | Closed (smoke test 6) |

---

## 8. Anomaly flags (rule-based, suggest only)

The brief says the machine must never move money on its own: a person authorises, and both the
suggestion and the decision are recorded. The rules are LARGE_AMOUNT, FOREIGN_PROBE,
REPEATED_REFUSALS, RAPID_DEBITS and NEAR_DRAIN.

| # | Threat | What stops it | Status |
|---|---|---|---|
| F1 | The flagging code moves money or freezes accounts by itself | Flags are only inserted into `anomaly_flags`. CONFIRMED does nothing automatically; an admin freezes an account separately through the normal endpoint. | Closed (`AnomalyTest`: balances unchanged by flagging) |
| F2 | A bug in a flag rule rolls back or blocks a real payment | Flags are evaluated **after** the money transaction commits (`@TransactionalEventListener(AFTER_COMMIT)` + `REQUIRES_NEW`), and errors are caught and logged | Closed |
| F3 | A flag or a review decision is edited or deleted afterwards | `anomaly_flags` and `flag_reviews` are append-only (SELECT/INSERT grants only, plus the same triggers). One review per flag (`UNIQUE flag_id`), and a second review gets 409. | Closed (`AnomalyTest`) |
| F4 | A holder sees flags, or an auditor makes decisions | Flags are readable by ADMIN/AUDITOR only; reviews are ADMIN only | Closed (`AnomalyTest`) |
| F5 | The server crashes between the money commit and the flag insert | The flag for that transaction is lost; the money movement itself is safe | **Accepted.** Flags are advisory. A periodic re-scan could backfill missed ones; not built. |
| F6 | The rules miss real fraud or flag normal behaviour | Fixed thresholds, no learning | **Accepted.** Rule-based only, no ML/LLM. Thresholds are configurable. |

---

## 9. Performance trade-offs (correct, but slower)

- **All deposits and withdrawals lock the single EXTERNAL row**, so they run one at a time across the whole system.
  That's correct but a bottleneck. **Accepted** at demo scale. A fix would be several EXTERNAL "shard" accounts.
- **One DB query per request** to load the user (see A5). **Accepted** in exchange for instant disable and role changes.

---

## Not built (deliberately)

- Overdrafts, holds, reversals and refunds, scheduled or batch transfers, multi-currency
- Maker-checker approval for large admin deposits
- ML/LLM-based anomaly detection (the flags are rule-based, see section 8)
- Email verification and KYC
- Anchoring the hash chain outside the database (see L3)
