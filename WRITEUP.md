# Write-up — LedgerLine

> **DRAFT. Rewrite this in my own words before submitting, and read it out loud once. If I can't say a sentence naturally, change it.**

## What I built

LedgerLine is a system that moves money between accounts. It has three roles:
- **Holders** request accounts and move their own money.
- **Admins** manage users, approve accounts and can deposit.
- **Auditors** can see everything and change nothing.

The backend is Java 21 and Spring Boot 3.5, with PostgreSQL for all data and Redis for rate limiting. The frontend is React.

The main parts:
- Login with JWT. The role is read from the database on every request, so disabling a user works immediately.
- A first-admin bootstrap that only runs from environment variables, and only when no admin exists.
- Account onboarding: PENDING → admin approves → ACTIVE, plus freeze and unfreeze.
- Deposits, withdrawals and transfers, all double-entry.
- Idempotency keys on every money request.
- A per-account statement that shows debits, credits **and refused attempts**, with the running balance and who started each one.
- An append-only ledger, enforced by the database itself.
- An auditor reconciliation page that checks the books balance.
- Redis rate limiting on login, register and money endpoints.
- Health checks: readiness fails if Postgres or Redis is down.
- Automated tests against a real Postgres, plus an end-to-end smoke test script.

## What I deliberately did not build

Overdrafts, holds, reversals and refunds, scheduled or batch transfers, multiple currencies, email verification and KYC, and a second approval for large admin deposits. A small system whose balances are always right is better than a big one that loses a rupee. The full list is in THREATS.md.

## My integrity and concurrency model

**The ledger.** Every money movement is double-entry: one debit line and one credit line that add up to zero. Deposits and withdrawals use a special EXTERNAL account that stands for "the outside world", so they're double-entry too. That gives one simple rule I can check at any time: **the sum of all ledger entries is 0**, and **every account's balance equals the sum of its own entries.** Money is stored as whole paise in `BIGINT`, never as decimals.

**One request = one database transaction**, in this order:
1. **Claim the idempotency key.** I insert the key into a table with a unique index (`INSERT ... ON CONFLICT DO NOTHING`). If the same key is already being processed, Postgres makes my insert wait until the first one finishes. If the key already exists, I return the stored result and move nothing.
2. **Lock the accounts** with `SELECT ... FOR UPDATE`, always the lower id first, so two opposite transfers can't deadlock.
3. **Check permission and balance**, under the lock, so nothing can change them in the meantime.
4. **Write.** Either both balances, the transaction row and two ledger entries, or a REJECTED row if it was refused. A refusal still commits, so it shows on the statement.
5. **Commit.** All of it happens, or none of it does.

**Where the balance is checked and the debit is written:** both in `MoneyService`, inside the same `@Transactional` method, steps 3 and 4. They happen atomically because they're in one transaction, and the account row is locked from before the check until after the write.

**Two withdrawals at the same instant:** the second one waits at the lock. When the first commits, the second reads the **new** balance and is refused if there isn't enough.

**The same request twice:** the second one waits at the key insert, then finds the key and returns the first answer. `replay_count` on the key shows in the data that it was a duplicate.

**Why row locks and not SERIALIZABLE:** SERIALIZABLE would also be correct, but Postgres then aborts conflicting transactions and I'd need retry logic. An explicit lock is easier to reason about and to explain.

**Append-only:** the app logs in as `ledger_app`, which only has SELECT and INSERT on the ledger tables. It cannot UPDATE or DELETE them at all. On top of that, a trigger blocks UPDATE, DELETE and TRUNCATE even for the table owner. The honest limit: the owner can still disable the trigger. That's in THREATS.md (L3).

**Authorisation:** every rule about who can move what lives in one class, `AccountAccessPolicy`, and it runs on the server on every request after the accounts are locked. A holder asking about someone else's account gets 404. An admin trying to withdraw from a customer gets 403. Auditors can't write anything.

## How I proved it

- `ConcurrencyTest`: 50 withdrawals at the same instant against a balance that covers only 3 of them. Exactly 3 succeed, the balance is never negative, and the balance equals the sum of the ledger entries.
- `IdempotencyTest`: the same key from 20 threads at once gives one transaction.
- `AuthorisationTest`: one user can't move another user's money.
- `ConservationTest`: 200 random concurrent operations, then the books still balance.
- `LedgerImmutabilityTest`: ledger rows can't be changed or deleted.
- **I removed the lock and ran the tests again:** `<TODO: what failed>`. That shows the tests really test the race.

## How I used AI, and where it was wrong

I used Cursor to write and run the code, and Claude to plan, write the prompts and review Cursor's output. I set up the machine, ran things and made the decisions.

The most useful catches were:
- Hibernate updating every column, which clashed with my column-level DB permissions
- a `@Transactional` that would have erased failed-login audit records
- Jackson silently turning 12.5 into 12
- **my own spec leaking which accounts exist**

Details are in PROMPTS.md.

## What is unfinished

- `<TODO: deployment status, e.g. "Deployed on Railway at ..." or what's not working yet>`
- The owner can still disable the append-only trigger (THREATS.md L3).
- Idempotency keys are kept forever (no expiry).
- No fault-injection test for a crash in the middle of a transfer. I rely on Postgres transaction atomicity.
- No automated tests for the frontend. It was checked by hand and through the API smoke test.
- No Content-Security-Policy header.
- All deposits and withdrawals queue on the single EXTERNAL row. That's correct but slow at high volume.
