# PROMPTS.md

How I used AI to build LedgerLine, and the places where it was wrong.

**Tools and who did what:**
- **Cursor (Agent mode):** wrote the code and ran builds and tests once my machine had Java and Docker.
- **Claude (chat):** planning, splitting the work into steps, writing the prompts I gave Cursor, and **reviewing Cursor's reports** before I accepted them.
- **Me:** set up the machine, made the design decisions, ran and checked things, and decided what to accept. I used one AI to check the other on purpose, because a single tool grading its own work is weak evidence.

The **"Caught by"** line in each entry says honestly who found the problem.

> Commit links: replace each `<commit>` with the real link (`git log --oneline` shows the hashes).

---

## 1. 2026-11-10: Version numbers from the AI

**Asked:** Cursor to scaffold the backend on Spring Boot 3.5.x with Flyway and PostgreSQL.

**Produced:** Spring Boot `3.5.16` and a Maven wrapper for Maven `3.9.16`.

**What was wrong / the risk:** AI tools often invent version numbers that look real. I couldn't confirm either one existed from the text alone. Cursor's first pass also **didn't create `mvnw` at all**, so the run instructions it gave me would have failed.

**What I did:** didn't trust the versions until a real build. On the first `mvnw spring-boot:run` both resolved from Maven Central, and the log showed `Spring Boot :: (v3.5.16)`. Cursor added the missing wrapper and a `.gitattributes` so `mvnw` keeps Unix line endings.

**Caught by:** Cursor (missing wrapper, when asked to verify against a checklist); me (versions, by running it).
**Commit:** `<commit>`

---

## 2. 2026-11-10: Column-level DB permissions vs Hibernate "update every column" (access control)

**Asked:** Cursor to give the app's database login (`ledger_app`) only column-level UPDATE rights, e.g. `UPDATE (balance, status, name) ON accounts`, and no UPDATE or DELETE at all on ledger tables.

**Produced:** correct grants. But the **entities from the previous step** still had every column updatable.

**What was wrong:** by default Hibernate's UPDATE statement sets **every** mapped column, not only the changed ones. So disabling a user would have sent `UPDATE users SET email=..., status=...`, and Postgres would reject it with "permission denied" because `ledger_app` can't update `email`. The two steps were each fine alone and broken together.

**What I did:** accepted Cursor's fix. Columns the app may not change are `updatable = false` with no setters, and `AuditLog` became `@Immutable` like the ledger entities. Lesson: DB grants and ORM behaviour have to be designed together.

**Caught by:** Cursor, while implementing the grants.
**Commit:** `<commit>`

---

## 3. 2026-11-10: A failed login would erase its own audit record (audit trail)

**Asked:** Cursor to write `LOGIN_FAILED` and `LOGIN_SUCCESS` rows to `audit_log`.

**What would have gone wrong:** the usual pattern is `@Transactional` on the service method. Here that's a bug: a wrong password throws a 401 exception, the exception **rolls back the transaction**, and the `LOGIN_FAILED` row disappears with it. Failed logins, which are the ones you most want to see, would never be recorded.

**What I did:** kept `login` without `@Transactional`, so the audit insert commits on its own before the 401 is thrown.

**Caught by:** Cursor, flagged in its report for me to check.
**Commit:** `<commit>`

---

## 4. 2026-11-10: Tests that would have proved nothing (access control / test validity)

**Asked:** for integration tests against a real Postgres using Testcontainers.

**What was wrong:** the easiest Spring Boot way, `@ServiceConnection`, makes the app connect as the container's **superuser**. Then the test "the app cannot delete ledger rows" would pass for the wrong reason, or fail, and every permission test would be meaningless.

**What I did:** the test base creates the two real roles in the container and wires the app as `ledger_app` and Flyway as `ledger_owner` with `@DynamicPropertySource`. `LedgerImmutabilityTest` then checks real "permission denied" and trigger errors.

**Caught by:** Claude, while we were writing the test prompt. It was specified before Cursor wrote any test code.
**Commit:** `<commit>`

---

## 5. 2026-11-10: Jackson silently turned 12.5 into 12 (money integrity)

**Asked:** Cursor for deposit, withdraw and transfer endpoints with `amountPaise` as a whole number.

**What was wrong:** by default Jackson accepts `"amountPaise": 12.5` and **truncates it to 12** without any error. In a money system that's a silent change to the amount the user asked for.

**What I did:** turned off float-to-int coercion in `application.yml`, so a decimal amount is now a 400. The smoke test checks it (step 25c). The frontend also converts rupees to paise by string parsing, never `parseFloat * 100`.

**Caught by:** Cursor, while writing the money endpoints.
**Commit:** `<commit>`

---

## 6. 2026-11-10: Our own spec leaked which accounts exist (authorisation)

**Asked:** "an account you cannot view returns 404, so we don't reveal it exists". In the same spec, an inactive destination returned 409.

**What was wrong:** **the spec itself.** Cursor pointed out that a holder could probe ids: a missing id gave 404, but a frozen account gave 409, which reveals that it exists. After the money step it found a second version: "not yours" was a recorded refusal (with a replay header) while "doesn't exist" was a plain error, so the two were still distinguishable.

**What I did:**
- Any unavailable destination (missing, frozen, EXTERNAL) now returns the **same** 404.
- For the second leak I decided to **accept** it, because account ids are sequential and existence is guessable anyway. Recording the attempt on the victim's statement is worth more. It's written in THREATS.md (S8).
- I also hid the prober's email from the victim: holders see "another user", while admin and auditor see the real email.

**Caught by:** Cursor (both leaks). The decision was made by Claude and me.
**Commit:** `<commit>`

---

## 7. 2026-11-1_: Removing the lock to prove the tests are real (concurrency) — TODO

> **TODO, do this myself before submitting.**

**What I did:** in `MoneyService` I replaced `findByIdForUpdate` (`SELECT ... FOR UPDATE`) with `findById` and ran `ConcurrencyTest` and `ConservationTest`.

**Result:** `<paste which assertion failed and the message>`

**What it proves:** the tests really exercise the race. Without the row lock, concurrent withdrawals `<overdrew / lost updates>`, and the test caught it. With the lock restored, all tests pass again.

**Caught by:** me.
**Commit:** no commit (experiment reverted). Note the date and time.

---

## 8. 2026-11-10: Rate limiting: three ways it could have quietly broken (access control)

**Asked:** for a Redis rate limiter on login, register and money endpoints.

**What could go wrong, and what we did:**
- **INCR then EXPIRE as two calls:** if the app dies between them, the key never expires and the user is locked out forever. *Fix:* one atomic Lua script. Specified up front by Claude.
- **The filter registered twice:** Spring Boot automatically registers any `Filter` bean as a servlet filter too, so it would run twice and every limit would be halved. *Fix:* the filter is created inside `SecurityConfig` and isn't a bean. Specified by Claude; Cursor confirmed.
- **The test couldn't turn rate limiting back on:** our spec disabled it in the shared test base, but `@DynamicPropertySource` outranks `@TestPropertySource`, so `RateLimitTest` could never re-enable it. *Fix:* Cursor moved it to `@TestPropertySource`. **Caught by Cursor, correcting our spec.**
- **Fail-open wasn't really fast:** Redis' default timeouts (10s to connect, 60s per command) meant every request would hang for a minute when Redis was down. *Fix:* 1-second timeouts. **Caught by Cursor.**

**Verified:** with Redis stopped, readiness returned 503, login still worked, and a WARN was logged.
**Commit:** `<commit>`

---

## 9. 2026-11-10: Demo data must not bypass the ledger (money integrity)

**Asked:** for seed accounts with money in them for the live demo.

**The risk:** the quick way to seed is `UPDATE accounts SET balance = 50000`. That creates money with no ledger entries, breaks the conservation invariant, and the auditor's reconciliation would show "NOT BALANCED" on day one.

**What I did:** the prompt required every seed deposit and transfer to go through `MoneyService` with fixed idempotency keys. `DemoSeedTest` checks each seed key is linked to a POSTED transaction, and reconciliation returned `balanced=true` after seeding.

**Caught by:** Claude, as a requirement in the prompt before the code was written.
**Commit:** `<commit>`

---

## What I learned

- The AI was usually right about framework details (Hibernate, Jackson, Spring test precedence) and **sometimes our own spec was the thing that was wrong**. Entries 6 and 8 are cases where Cursor corrected the spec.
- Nothing counts until it runs. Six steps were written before my machine could build them, and the first real build was the first real check.
- The tests only matter if they fail when the protection is removed (entry 7).
