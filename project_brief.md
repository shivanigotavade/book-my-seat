# Book My Seat — High-Concurrency Seat Reservation Service

> **Take-home:** Seat Reservation at Scale · Deploy & Observe round · Backend Engineering, Paytm Money
> **Stack:** Java 21 · Spring Boot 3.x · Apache Maven · PostgreSQL 16
> **Time budget:** ~1 day · **AI tools:** allowed and expected (disclosed honestly in `WRITEUP.md`)

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Tech Stack & Versions](#2-tech-stack--versions)
3. [Assumptions, Decisions & Out of Scope](#3-assumptions-decisions--out-of-scope)
4. [Goals Index](#4-goals-index)
5. [Goals in Detail](#5-goals-in-detail)
6. [API Contract Summary](#6-api-contract-summary)
7. [Error Model](#7-error-model)
8. [Repository Layout](#8-repository-layout)
9. [Definition of Done (mapped to the grader's checks)](#9-definition-of-done-mapped-to-the-graders-checks)
10. [Suggested Timeline & Commit Plan](#10-suggested-timeline--commit-plan)
11. [Interview Readiness](#11-interview-readiness)
12. [Amendments & Follow-up Goals](#12-amendments--follow-up-goals)

---

## 1. Project Overview

### 1.1 Name
**Book My Seat** — a seat-reservation system of record built for on-sale stampedes.

### 1.2 Description
Book My Seat is a JSON HTTP service that sells **uniquely assigned seats** for an event (concert or movie hall). When a show goes on sale, tens of thousands of buyers hit "book" within the same second, many fighting over the same few "good" seats. Book My Seat is the **system of record** that decides — atomically and correctly — who gets each seat and who is turned away.

The service is judged on the **running system**, not the write-up: the reviewers will clone it, deploy-check it, and fire their own concurrency bursts at the live URL.

### 1.3 Non-negotiable guarantees
| # | Guarantee | Meaning |
|---|-----------|---------|
| 1 | **No double-sell** | A seat held/confirmed for one user can never be confirmed for another. 500 users racing for `A12` → exactly one `201`, 499 clean `409`. |
| 2 | **Per-user limit** | A user never holds more than `per_user_limit` (default 4) seats per show, even with 10 parallel requests. |
| 3 | **Idempotency** | Same key → exactly one reservation. Retry returns the original. Same key + different body → `409`. |
| 4 | **Zero 5xx under burst** | Declines are 4xx domain outcomes. Contention, deadlocks and pool pressure never leak as 500s. |
| 5 | **Reconciliation invariant** | `available + held + confirmed == total_seats` — to the unit, during and after the burst. |
| 6 | **Token-derived identity** | The caller is whoever the token says. Spoofed body fields are ignored; users can only cancel their own reservations. |
| 7 | **Observable** | Health, readiness, Prometheus metrics and structured logs let anyone watch the system behave correctly in real time. |
| 8 | **Money is integer paise** | No floats anywhere — `BIGINT` in the DB, `long` in Java. |

### 1.4 Success criteria (one line)
> A clean `git clone` builds and runs via Docker; the live URL survives a cold start and a 20,000-request burst with all invariants intact, and the metrics reconcile with the API state.

---

## 2. Tech Stack & Versions

| Concern | Choice | Notes |
|---------|--------|-------|
| Language / runtime | Java 21 (LTS) | Virtual threads (`spring.threads.virtual.enabled=true`) to hold thousands of in-flight requests cheaply |
| Framework | Spring Boot 3.3+ | Web, Validation, Security, Actuator |
| Build | Apache Maven 3.9+ (with Maven Wrapper) | `./mvnw` committed so clean checkouts build without a local Maven |
| Datastore | PostgreSQL 16 | Single database — the atomic decision lives here |
| Data access | Spring Data JPA repositories + entities (native SQL for guarded updates and lock ordering) | Explicit SQL for guarded updates and lock ordering; Flyway owns DDL, entities are a read/write view only |
| Migrations | Flyway | Schema versioned in `src/main/resources/db/migration` |
| Connection pool | HikariCP | Explicitly sized (see G12) |
| Auth | Spring Security + JWT (HS256) | Role claim: `USER` / `ADMIN` |
| Metrics | Micrometer + `micrometer-registry-prometheus` | Exposed at `/metrics` |
| Logging | Logback + `logstash-logback-encoder` (JSON) | MDC carries `request_id`, `user_id` |
| Testing | JUnit 5, Testcontainers (Postgres), AssertJ | Concurrency tests run against a real Postgres |
| Containers | Docker multi-stage build + Docker Compose | Same image locally and in prod |
| Deploy target | Render / Railway / Fly.io (free tier) | Managed Postgres or container Postgres |
| Burst tooling | Java 21 single-file program (or k6) wrapped by `burst.sh` / `make burst` | Prints outcome distribution + reconciliation |

---

## 3. Assumptions, Decisions & Out of Scope

### 3.1 Design decisions (locked in up front)

| Topic | Decision | Rationale |
|-------|----------|-----------|
| Hold model | **Explicit cancel** (`POST /reservations/{id}/cancel`). Reserve is confirmed immediately. | Fewer moving parts and failure modes than TTL expiry; no sweeper race with confirmation. TTL expiry listed as a "next step". |
| Multi-seat requests | **All-or-nothing** | Simple to reason about, easy to hold under concurrency, no partially-granted surprises. |
| Atomic mechanism | **Row locks in deterministic (sorted) order + guarded `UPDATE … WHERE status='available'`**, with DB constraints as a safety net | Race-free by construction; sorted lock order prevents deadlock on multi-seat. |
| Per-user limit mechanism | **Conditional counter update** on a `(user_id, show_id)` quota row | Serialises parallel requests from the same user without locking seats. |
| Idempotency scope | Key is **scoped per user**; request fingerprint = hash of `(show_id, sorted seats)` | Prevents one user's key colliding with another's; detects same-key-different-body. |
| Idempotency storage | Postgres table with `UNIQUE (user_id, idempotency_key)`, written **in the same transaction** as the reservation | Exactly-once is enforced by the database, not by app memory. |
| Declines & keys | A declined attempt rolls back and **does not consume** the key | Keeps the key table = "keys that produced a reservation"; retry after a decline is safe. |
| Replay status code | **`200 OK`** with the original body + `Idempotent-Replayed: true` header | Keeps "exactly one `201` per hot seat" true even when the winner retries. |
| Non-owner cancel | **`403 FORBIDDEN`** | Explicit and testable. |
| Repeat cancel | **`200 OK`**, status `cancelled` (idempotent) | Safe for client retries. |
| Quota semantics | Counts seats in `held` or `confirmed` state; cancelling decrements | Matches "cannot hold more than N seats". |

### 3.2 Assumptions to state in the README
- **Token acquisition for reviewers:** the brief says identity comes from a token but does not say how reviewers obtain one. Provide a documented, env-flagged `POST /auth/token` (`{"user_id":"u123"}` → USER JWT) for load testing, plus bootstrap-gated ADMIN issuance on the same endpoint (`{"user_id":"ops","role":"ADMIN"}` with the `ADMIN_TOKEN` secret as bearer → ADMIN JWT) and direct static-token auth as fallback. Document all clearly in the README and make open issuance switchable via `AUTH_DEV_TOKEN_ENDPOINT_ENABLED`.
- `per_user_limit` is a per-show setting, default `4`, overridable on show creation.
- A show has up to ~50,000 seats; seat labels are unique strings per show.

### 3.3 Out of scope
UI, real payment processing, notifications, multi-region, seat maps/pricing tiers, refunds. (Mention as "what I'd do next".)

---

## 4. Goals Index

| ID | Goal | Brief requirement it satisfies |
|----|------|-------------------------------|
| G1 | Project foundation & repo hygiene | Clean checkout builds and runs; incremental commits |
| G2 | Data model & migrations | Atomic decision lives in the DB |
| G3 | Authentication & identity | Token-derived identity; admin vs user |
| G4 | Create show | `POST /shows` |
| G5 | Reserve seat — the atomic core | No double-sell; clean 409 |
| G6 | Per-user limit under concurrency | Limit holds under parallel requests |
| G7 | Idempotency | Exactly-once; same-key-different-body → 409 |
| G8 | Multi-seat behaviour & deadlock avoidance | Partial-request policy holds under concurrency |
| G9 | Release / cancel | Owner-only cancel; never resurrect a sold seat |
| G10 | Show state & reconciliation invariant | `GET /shows/{id}` |
| G11 | Error handling & zero-5xx policy | Zero 5xx across the burst |
| G12 | Concurrency & performance tuning | Survive ~20k concurrent reservations |
| G13 | Health: liveness & readiness | Readiness fails closed on DB down |
| G14 | Metrics (Prometheus) | Counters/gauges reconcile with API state |
| G15 | Structured logging | Correlation id, public log access |
| G16 | Containerization | Dockerfile + Compose |
| G17 | Deployment | Public URL, cold-start safe |
| G18 | One-command burst script | Hot-seat storm + reconciliation output |
| G19 | Automated testing | Concurrency proofs against real Postgres |
| G20 | Documentation (README + WRITEUP) | Mandatory deliverables |
| G21 | Git history | Incremental commits show how work was done |
| G22 | AI usage disclosure | Honest "directed vs decided" |
| G23 | Properties configuration | Same externalised config in `application.properties` |
| G24 | JPA repositories & entities | Same guarantees behind repositories, native SQL on hot paths |
| G25 | Package consolidation | `entity` / `repository` / `controller` / `service` packages |
| G26 | Bootstrap admin issuance | Short-lived ADMIN JWTs via bootstrap secret |
| G27 | Postman collection & brief | Importable requests + request catalogue |
| G28 | Rolling file log capture | Same JSON lines in `target/logs/` for local runs |
| G29 | Live-burst hardening fixes | Timestamp defaults, flush ordering before guarded writes |

---

## 5. Goals in Detail

### G1 — Project foundation & repo hygiene
**Objective:** A repository anyone can clone, build, and run in minutes.

- [ ] Initialise Spring Boot project via Maven (`groupId: com.bookmyseat`, `artifactId: book-my-seat`), Java 21.
- [ ] Commit the Maven Wrapper (`mvnw`, `.mvn/`).
- [ ] Layered package structure: `config`, `security`, `show`, `reservation`, `idempotency`, `observability`, `web` (error handling), `common`.
- [ ] Externalised configuration through environment variables (`DATABASE_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET`, `ADMIN_TOKEN`, `PORT`, `HIKARI_MAX_POOL_SIZE`, etc.) with safe local defaults in `application.properties`.
- [ ] `.gitignore`, `.dockerignore`, `.editorconfig`.
- [ ] `Makefile` with targets: `build`, `test`, `run`, `up`, `down`, `burst`.

**Acceptance:** `git clone && docker compose up` yields a healthy service on `localhost:8080`.

---

### G2 — Data model & migrations
**Objective:** Make illegal states unrepresentable; let the DB be the last line of defence.

**Tables (Flyway `V1__init.sql`):**

| Table | Key columns | Purpose |
|-------|-------------|---------|
| `shows` | `id UUID PK`, `name`, `price_paise BIGINT CHECK (>0)`, `per_user_limit INT DEFAULT 4 CHECK (>0)`, `total_seats INT`, `created_at` | Show definition |
| `seats` | `id BIGSERIAL PK`, `show_id FK`, `seat_label`, `status` (`available`/`held`/`confirmed`), `reservation_id UUID NULL`, `user_id TEXT NULL`, `version`/`updated_at`; **`UNIQUE (show_id, seat_label)`** | One row per seat; the contested resource |
| `reservations` | `id UUID PK`, `show_id`, `user_id`, `status` (`confirmed`/`cancelled`), `amount_paise BIGINT`, `created_at`, `cancelled_at` | Business record |
| `reservation_seats` | `reservation_id`, `show_id`, `seat_label`, `active BOOLEAN`; **partial unique index `ON (show_id, seat_label) WHERE active`** | Safety net: a double-hold is impossible even if app logic is wrong |
| `user_show_quota` | `PK (user_id, show_id)`, `active_seats INT CHECK (active_seats >= 0)` | Per-user limit counter |
| `idempotency_keys` | `PK/UNIQUE (user_id, idempotency_key)`, `request_hash`, `reservation_id`, `created_at` | Exactly-once enforcement |

**Constraints & indexes**
- [ ] `CHECK` on `seats`: `status='available'` ⇒ `reservation_id IS NULL AND user_id IS NULL`; `status<>'available'` ⇒ both NOT NULL.
- [ ] Index `seats (show_id, status)` for counts; index `reservations (user_id)`.
- [ ] Money columns are `BIGINT` only.
- [ ] Seed insertion done via batched/`unnest` insert so a 50k-seat show creates quickly.
- [ ] JPA entities initialize every DB-defaulted column (e.g. `created_at`) in Java: Hibernate inserts explicit `NULL`s, which override column defaults — relying on `DEFAULT now()` works for native SQL only.

**Acceptance:** Flyway applies cleanly on an empty DB; manually attempting to double-confirm a seat via SQL is rejected by a constraint.

---

### G3 — Authentication & identity
**Objective:** Identity comes from the token — never from the body.

- [ ] Stateless JWT (HS256, secret from env). Claims: `sub` (user id), `role` (`USER`/`ADMIN`), `exp`.
- [ ] Spring Security filter chain: `/health/**`, `/metrics`, `/auth/token` public; `POST /shows` requires `ADMIN`; reserve/cancel require authenticated `USER`; `GET /shows/{id}` public or authenticated (document the choice).
- [ ] Controllers read the user id **only** from `SecurityContext` / `Authentication` principal.
- [ ] Any `user_id` field in a request body is ignored (and never bound to the DTO).
- [ ] Token endpoint `POST /auth/token` gated by `AUTH_DEV_TOKEN_ENDPOINT_ENABLED` — issues USER tokens for load testing; `role: ADMIN` additionally requires the bootstrap `ADMIN_TOKEN` secret and mints short-lived ADMIN JWTs.
- [ ] `401` for missing/invalid token, `403` for insufficient role.

**Acceptance:** A request with token for `alice` and body `{"user_id":"bob", ...}` produces a reservation owned by `alice`.

---

### G4 — Create show (`POST /shows`, admin)
**Objective:** Create a show with every seat `available`.

- [ ] Request: `{ "name": "...", "seats": ["A1","A2",...], "price_paise": 25000, "per_user_limit": 4 (optional) }`.
- [ ] Validation: non-blank name; non-empty seat list; **no duplicate labels** (normalised, trimmed); `price_paise` positive integer (reject decimals/strings); sensible max seats (e.g. 100,000); label length cap.
- [ ] Single transaction: insert show + bulk-insert seats (`INSERT … SELECT unnest(?)`).
- [ ] Response `201`: `{ id, name, price_paise, per_user_limit, total_seats, seats: [{seat, status:"available"}] }`.
- [ ] `400` for validation failures; `401/403` for non-admin.

**Acceptance:** Creating a 20k-seat show completes in a couple of seconds; `GET` shows `available == total_seats`.

---

### G5 — Reserve seat: the atomic core (`POST /shows/{id}/reserve`)
**Objective:** Exactly one winner per seat; every loser gets a clean `409`.

**Request:** `{ "seats": ["A12"], "idempotency_key": "…" }` (key may also arrive via `Idempotency-Key` header; header wins if both present and differ → `400`).

**Single-transaction algorithm (READ COMMITTED):**
1. Validate & normalise: trim, de-duplicate, **sort** seat labels; reject empty list (`400`); reject missing key (`400`).
2. If `len(seats) > per_user_limit` → `409 PER_USER_LIMIT` immediately.
3. Compute `request_hash = SHA-256(show_id | sorted seats)`.
4. `BEGIN`
5. **Idempotency gate** (G7).
6. **Quota gate** (G6).
7. **Lock seats in sorted order:**
   ```sql
   SELECT id, seat_label, status
   FROM seats
   WHERE show_id = :show AND seat_label = ANY(:labels)
   ORDER BY seat_label
   FOR UPDATE;
   ```
   - Fewer rows than requested → unknown seat → rollback, `404/400 INVALID_SEAT`.
   - Any row not `available` → rollback, `409 SEAT_TAKEN` (include which seats).
8. **Guarded write (belt and braces):**
   ```sql
   UPDATE seats
   SET status='confirmed', reservation_id=:rid, user_id=:uid, updated_at=now()
   WHERE show_id=:show AND seat_label = ANY(:labels) AND status='available';
   -- affected rows MUST equal len(labels), else rollback → 409 SEAT_TAKEN
   ```
9. Insert `reservations` (`amount_paise = price_paise × n`, using `Math.multiplyExact`) and `reservation_seats` rows.
10. Record `reservation_id` against the idempotency key; `COMMIT`.
11. Return `201` with `{ reservation_id, show_id, user_id, seats, amount_paise, status:"confirmed" }`.

**Why it is race-free (for the write-up):** the decision is taken while holding a row lock on every requested seat, and the state check is re-evaluated *after* the lock is acquired. 500 concurrent transactions on `A12` queue behind the first; the winner commits `confirmed`; each subsequent transaction acquires the lock, sees `confirmed`, and declines. The partial unique index on `reservation_seats` makes a double-hold impossible even if this logic had a bug.

**Acceptance:**
- [ ] 500 parallel users → `A12`: exactly 1 × `201`, 499 × `409`, 0 × 5xx.
- [ ] No read-then-write anywhere on the reserve path.

---

### G6 — Per-user limit under concurrency
**Objective:** A user firing 10 parallel reserves on a `limit=4` show ends with ≤ 4 seats.

- [ ] Quota row upsert: `INSERT INTO user_show_quota(user_id, show_id, active_seats) VALUES (…, 0) ON CONFLICT DO NOTHING;`
- [ ] Conditional increment, inside the reserve transaction:
  ```sql
  UPDATE user_show_quota
  SET active_seats = active_seats + :n
  WHERE user_id=:uid AND show_id=:show AND active_seats + :n <= :limit;
  -- 0 rows affected → rollback, 409 PER_USER_LIMIT
  ```
- [ ] The row lock taken by this `UPDATE` serialises parallel requests from the same user; the counter rolls back automatically if the seat step fails.
- [ ] Cancel decrements the counter (same lock-order rules — see G8).
- [ ] Idempotent replays do **not** increment again.

**Acceptance:** 10 parallel single-seat reserves (distinct keys, distinct free seats) by one user → exactly 4 succeed, 6 get `409 PER_USER_LIMIT`; `user_show_quota.active_seats == 4`.

---

### G7 — Idempotency
**Objective:** Same key reserves exactly once; retries are free; mismatched bodies are rejected.

- [ ] Key source: `Idempotency-Key` header or `idempotency_key` body field; validated (length ≤ 128, printable).
- [ ] First statement in the transaction:
  ```sql
  INSERT INTO idempotency_keys(user_id, idempotency_key, request_hash)
  VALUES (:uid, :key, :hash)
  ON CONFLICT (user_id, idempotency_key) DO NOTHING;
  ```
  - **1 row inserted** → first time; continue.
  - **0 rows** → key exists (a concurrent same-key transaction blocks here until the first one commits or aborts, so there is no window for a double reserve). Load the row:
    - `request_hash` differs → `409 IDEMPOTENCY_KEY_REUSED` (counted under that reason).
    - hash matches → return the stored reservation with `200` + `Idempotent-Replayed: true`; **no seat, quota or counter movement**.
- [ ] If the first attempt is declined and rolls back, the key row disappears with it and the key is reusable.
- [ ] Replays increment `idempotent-replay` metric only.

**Acceptance:** 50 parallel requests with one key → 1 reservation row, 1 × `201`, 49 × `200` replays with the same `reservation_id`. Same key + `["A13"]` after `["A12"]` → `409`.

---

### G8 — Multi-seat behaviour & deadlock avoidance
**Objective:** A documented all-or-nothing policy that holds under concurrency.

- [ ] **Policy:** `["A12","A13"]` where only one is free → entire request declines with `409 SEAT_TAKEN`; nothing is held; quota and counters untouched.
- [ ] **Global lock order** used by every transaction type (reserve and cancel), documented in code and write-up:
  1. idempotency row (reserve only)
  2. `user_show_quota` row
  3. `seats` rows, **sorted by `seat_label`**
  4. `reservations` row
- [ ] Concurrent `["A1","A2"]` and `["A2","A1"]` requests both normalise to `[A1,A2]` → lock order identical → no deadlock.
- [ ] Safety net: catch SQLState `40P01` (deadlock) / `40001` (serialization failure) and retry the whole transaction up to 3× with jittered backoff — outside the transaction boundary, never leaking as 5xx.

**Acceptance:** 1,000 mixed multi-seat requests on overlapping seat pairs complete with zero deadlock errors reaching the client and no partially granted requests.

---

### G9 — Release / cancel (`POST /reservations/{id}/cancel`)
**Objective:** Owner-only cancel; the seat becomes cleanly re-bookable; a sold seat is never resurrected.

- [ ] Resolve the reservation (read for `show_id`/`user_id` only), then follow the global lock order: quota → seats (sorted) → reservation.
- [ ] Guarded reservation transition:
  ```sql
  UPDATE reservations SET status='cancelled', cancelled_at=now()
  WHERE id=:id AND user_id=:uid AND status='confirmed';
  ```
- [ ] Guarded seat release — only seats still tied to **this** reservation:
  ```sql
  UPDATE seats
  SET status='available', reservation_id=NULL, user_id=NULL, updated_at=now()
  WHERE reservation_id=:id AND status IN ('held','confirmed');
  ```
  Because the match is on `reservation_id`, a seat that was released and re-confirmed to someone else is untouched.
- [ ] Deactivate `reservation_seats` rows; decrement `user_show_quota.active_seats`.
- [ ] `403` if caller ≠ owner; `404` if reservation unknown; repeat cancel → `200` (idempotent, no double decrement).
- [ ] Metrics: `reservations_cancelled_total`, `seats_released_total`.

**Acceptance:** Cancel → re-reserve by another user succeeds; a late duplicate cancel of the old reservation does not free the new owner's seat; bob cannot cancel alice's reservation (even if he spoofs `user_id`).

---

### G10 — Show state & reconciliation invariant (`GET /shows/{id}`)
**Objective:** `available + held + confirmed == total_seats` at every instant.

- [ ] Response: `{ id, name, price_paise, per_user_limit, total_seats, counts:{available, held, confirmed}, seats:[{seat,status}] }`.
- [ ] Counts and seat list come from **one SQL statement** (or a single `REPEATABLE READ` read-only transaction) so they come from the same snapshot — otherwise the invariant can appear to break mid-burst.
- [ ] Optional `?summary=true` returns counts only (cheap to poll during a burst).
- [ ] `404` for unknown show.
- [ ] A DB-level reconciliation query is also exposed in the burst script's final check.

**Acceptance:** Polling the endpoint continuously during a 20k-request burst never observes a sum ≠ `total_seats`.

---

### G11 — Error handling & the zero-5xx policy
**Objective:** Every domain outcome is a 4xx; infrastructure hiccups are absorbed or shed as 4xx/503-readiness only.

- [ ] Global `@RestControllerAdvice` with a consistent error body (see §7).
- [ ] Domain exceptions: `SeatTakenException`, `PerUserLimitException`, `IdempotencyKeyReusedException`, `InvalidSeatException`, `ForbiddenException`, `NotFoundException`.
- [ ] Map malformed JSON, type errors and bean-validation failures to `400`.
- [ ] Transient DB errors (deadlock, serialization, lock timeout, pool timeout) → bounded retry (G8); if retries exhaust → `429 Too Many Requests` + `Retry-After` (a 4xx, never a 500).
- [ ] No stack traces in responses; full detail only in structured logs.
- [ ] The only intentional 5xx is `503` from `/health/ready` when the DB is down.

**Acceptance:** A 20k-request burst yields 0 × 5xx on business endpoints.

---

### G12 — Concurrency & performance tuning
**Objective:** Survive ~20,000 concurrent reservations on free-tier hardware without errors.

- [ ] Virtual threads enabled; Tomcat `max-connections` and `accept-count` raised so connections queue rather than get refused.
- [ ] HikariCP sized deliberately (e.g. `maximumPoolSize` ≈ 10–20, within the managed Postgres connection limit); `connectionTimeout` long enough (≥ 30s) that queued requests wait instead of failing.
- [ ] Optional bulkhead (`Semaphore`) matching pool size so waiting happens in cheap virtual threads, not in the pool.
- [ ] `lock_timeout` / `statement_timeout` set on the transaction to bound worst-case waits; handled via retry/429.
- [ ] Keep transactions short: no network calls, no logging inside the lock window beyond minimal.
- [ ] Hot-seat optimisation (optional): `FOR UPDATE NOWAIT`/`SKIP LOCKED` for fast-fail on single-seat requests — document the trade-off (a holder who later rolls back could cause a spurious decline).
- [ ] JVM flags for small containers: `-XX:MaxRAMPercentage=75`, `-XX:+UseG1GC` (or `-XX:+UseSerialGC` on very small instances).
- [ ] Verify with the burst script and capture latency percentiles (p50/p95/p99).

**Acceptance:** 20k concurrent requests against the deployed instance complete with 0 × 5xx; p99 latency documented.

---

### G13 — Health: liveness & readiness
**Objective:** Orchestrators and reviewers can tell "alive" from "ready".

- [ ] `GET /health/live` → `200 {"status":"UP"}` as long as the process is running; no dependency checks.
- [ ] `GET /health/ready` → runs `SELECT 1` with a short timeout (≈ 1–2s):
  - DB reachable → `200 {"status":"UP","db":"UP"}`
  - DB down/slow → `503 {"status":"DOWN","db":"DOWN"}` (**fails closed**)
- [ ] Wire Actuator health groups (`liveness`, `readiness`) to those paths; hide details from unauthenticated callers except the status.
- [ ] Platform health check points at `/health/ready`.
- [ ] Readiness is verified by stopping Postgres in Compose and observing `503` (documented in README).

**Acceptance:** Stop the DB → readiness `503` within seconds; start it → recovers to `200` without restarting the app.

---

### G14 — Metrics (Prometheus)
**Objective:** Watch the burst live; numbers reconcile with API state.

Exposed at `GET /metrics` (Prometheus text format).

| Metric | Type | Labels | Meaning |
|--------|------|--------|---------|
| `bookmyseat_reservations_confirmed_total` | Counter | `show_id` (optional) | Successful new reservations |
| `bookmyseat_reservations_declined_total` | Counter | `reason` = `seat-taken` \| `per-user-limit` \| `idempotent-replay` (+ `idempotency-key-reused`, `invalid-seat`) | Declines/replays by reason |
| `bookmyseat_reservations_cancelled_total` | Counter | — | Cancellations |
| `bookmyseat_seats_released_total` | Counter | — | Seats returned to the pool |
| `bookmyseat_seats_available` | Gauge | `show_id` | Seats currently available |
| `bookmyseat_seats_held` / `bookmyseat_seats_confirmed` | Gauge | `show_id` | Current counts |
| `bookmyseat_tx_retries_total` | Counter | `cause` | Deadlock/serialization retries |
| `http_server_requests_seconds` | Histogram | `uri`, `status` | Request latency & status mix |
| `hikaricp_*`, `jvm_*` | — | — | Pool and JVM health |

- [ ] Gauges are computed from the **database** (cheap grouped query with a ≤1–2s cache) so they cannot drift from reality after restarts.
- [ ] Counters increment **only after commit** (no counting rolled-back attempts as confirmed).
- [ ] Keep label cardinality low (cap `show_id` labels or aggregate).
- [ ] **Reconciliation rule:** `total_seats − seats_available == Σconfirmed − Σseats_released` per show; the burst script asserts it.
- [ ] README includes sample Prometheus queries and an optional Compose profile with Prometheus + Grafana.

**Acceptance:** After a burst, `confirmed_total` equals the number of `201`s the client saw, and `seats_available` equals `GET /shows/{id}` `counts.available`.

---

### G15 — Structured logging
**Objective:** Trace any request end to end; show the system behaving under load.

- [ ] JSON logs to stdout via `logstash-logback-encoder`.
- [ ] Same JSON lines appended to `${LOG_PATH}/book-my-seat-api.log` (default `target/logs/`, rolling 100MB/30d) for local run capture; `LOG_PATH` overrides per run.
- [ ] Servlet filter sets `request_id` (from `X-Request-Id` if supplied, else generated UUID), puts it in MDC, and echoes it in the response header and error bodies.
- [ ] MDC also carries `user_id`, `show_id`, `idempotency_key` (hashed/truncated), `outcome`.
- [ ] One concise log line per reservation decision: `reservation.confirmed`, `reservation.declined reason=seat-taken`, `reservation.replayed`, `reservation.cancelled`.
- [ ] Log level controlled by env; avoid per-request DEBUG noise during bursts; never log secrets/tokens.
- [ ] **Public log access:** link to the platform's public log view if supported, otherwise include a short screen recording of live logs under burst in the repo/README.

**Acceptance:** Given an error response's `request_id`, the matching log lines can be found in one search.

---

### G16 — Containerization
**Objective:** The image you run locally is the image you deploy.

- [ ] Multi-stage `Dockerfile`: Maven build stage (dependency layer cached) → slim JRE 21 runtime stage; non-root user; `HEALTHCHECK` on `/health/live`.
- [ ] `docker-compose.yml`: `postgres:16` (named volume, healthcheck) + `app` (depends_on healthy DB) + optional `prometheus` profile.
- [ ] App reads `PORT` env (required by most PaaS).
- [ ] Flyway migrates on startup; app waits/retries for DB gracefully.
- [ ] `docker compose up --build` works from a clean clone with no manual steps.

**Acceptance:** Fresh clone on a machine with only Docker installed reaches `/health/ready = 200`.

---

### G17 — Deployment (Render / Railway / Fly.io)
**Objective:** A live public URL that survives a cold start and comes up healthy.

- [ ] Deploy from the repo's Dockerfile; attach managed Postgres (or sidecar) and set env vars (`DATABASE_URL`, `JWT_SECRET`, `ADMIN_TOKEN`, `AUTH_DEV_TOKEN_ENDPOINT_ENABLED`, `HIKARI_MAX_POOL_SIZE`, `PORT`).
- [ ] Health check path: `/health/ready`.
- [ ] Cold-start hardening: lazy work minimised, Flyway fast, JVM memory flags tuned to the free-tier RAM, DB connection retry on boot.
- [ ] Verify: redeploy from scratch, wait for sleep/wake, confirm service reaches healthy without manual intervention.
- [ ] Note free-tier limits (sleep policy, DB expiry, connection caps) in the README so reviewers know what to expect — and consider a keep-warm note for the review window.
- [ ] README lists: live base URL, how to get a token, how to create a show, sample `curl`s.

**Acceptance:** Live URL responds on `/health/ready`; the burst script runs against it successfully.

---

### G18 — One-command burst script
**Objective:** Reproduce the on-sale stampede against the live URL and print the verdict.

Entry points: `make burst BASE_URL=https://…` and `./burst.sh <BASE_URL>` (wraps a Java 21 virtual-thread program, or k6).

**Scenarios**
| # | Scenario | Expected |
|---|----------|----------|
| 1 | Setup: admin creates a fresh show (e.g. 20,000 seats) | `201` |
| 2 | **Hot-seat storm:** 500 distinct users → same seat `A12` | exactly 1 × `201`, 499 × `409 seat-taken` |
| 3 | **On-sale stampede:** ~20,000 concurrent reserves, many users, heavy overlap on a small hot set, some with duplicate keys | 0 × 5xx; no seat confirmed twice |
| 4 | **Idempotent retries:** same key ×N | 1 reservation; replays return same id |
| 5 | **Same key, different seats** | `409` |
| 6 | **Per-user limit:** one user, 10 parallel reserves, limit 4 | ≤ 4 confirmed |
| 7 | **Spoofed identity:** body `user_id` ≠ token user | reservation owned by token user |
| 8 | **Cancel authorisation:** non-owner cancel | `403`; owner cancel → `200`, seat re-bookable |
| 9 | **Reconciliation:** `GET /shows/{id}` + `/metrics` | `available+held+confirmed == total`; metrics match client tallies |

**Output**
- [ ] Outcome distribution table: confirmed / declined by reason / replays / 4xx other / **5xx** / network errors.
- [ ] Latency percentiles (p50/p95/p99) and throughput.
- [ ] Per-hot-seat winner check; final invariant check; metrics-vs-API reconciliation.
- [ ] Clear `PASS`/`FAIL` per check and a **non-zero exit code** on any failure.
- [ ] Configurable via flags/env: `USERS`, `REQUESTS`, `HOT_SEATS`, `TOKEN_URL`, `ADMIN_TOKEN`.

**Acceptance:** Running the script against the live URL prints all-green with zero 5xx.

---

### G19 — Automated testing
**Objective:** Prove the guarantees continuously, against a real Postgres.

- [ ] **Testcontainers** Postgres for all integration tests (no H2 — lock semantics must match prod).
- [ ] Concurrency tests using `ExecutorService`/virtual threads + `CountDownLatch` start gate:
  - hot-seat race (500 threads) → 1 winner
  - per-user limit race (10 threads, limit 4)
  - idempotency race (50 threads, one key)
  - same key / different body
  - overlapping multi-seat requests (deadlock check)
  - cancel/reserve race on the same seat (never resurrect, never double-sell)
  - invariant check at the end of every test
- [ ] Unit tests: request normalisation, hash computation, amount calculation (overflow), error mapping.
- [ ] Security tests: missing/invalid token, role enforcement, spoofed `user_id`, non-owner cancel.
- [ ] Health tests: readiness flips to `503` when the datasource is unavailable.
- [ ] CI (GitHub Actions): `./mvnw verify` on every push.

**Acceptance:** `./mvnw verify` is green from a clean checkout; concurrency tests are stable across repeated runs.

---

### G20 — Documentation
**README.md**
- [ ] What it is, live URL, quick start (Docker), env var table.
- [ ] How to obtain tokens; sample `curl` for every endpoint.
- [ ] **How to run the burst script** (local and against live URL).
- [ ] Where to find metrics, health endpoints, and logs (public link or recording).
- [ ] Design decisions summary (hold model, all-or-nothing policy, replay status code).

**WRITEUP.md** (required sections)
- [ ] **The atomic decision** — exact mechanism, why race-free, how multi-seat avoids deadlock (sorted lock order).
- [ ] **Idempotency** — where the key is stored, how exactly-once is enforced (unique constraint + same transaction), same-key-different-body handling.
- [ ] **Holds & expiry** — explicit-cancel model, why chosen, how it never resurrects a seat.
- [ ] **Consistency vs availability under a partition** — single Postgres primary as source of truth; choose consistency (fail closed, readiness 503) over availability; what happens on failover.
- [ ] **Observability** — what you'd be **paged for at 2am** (readiness failing, 5xx rate > 0, p99 latency, Hikari pool saturation, invariant gauge drift, deadlock-retry spike, `reservations_confirmed` rate anomalies).
- [ ] **AI usage** — see G22.
- [ ] **What I'd do next** — TTL holds + expiry sweeper, payment step & saga, per-user/IP rate limiting, virtual waiting room, sharded hot shows, Redis pre-filter, read replicas for show state, outbox events.

---

### G21 — Git history
**Objective:** Reviewers read how the work was actually done.

- [ ] Commit small and often with conventional messages (`feat:`, `fix:`, `test:`, `chore:`, `docs:`).
- [ ] Make mistakes visible and fix them forward — do not squash into one commit.
- [ ] Suggested sequence in §10.

---

### G22 — AI usage disclosure (honest, specific)
**Objective:** Show that the depth is genuinely yours — you will extend this live in the interview.

- [ ] Keep a running log while building: *what I asked the AI to do · what it produced · what I changed or rejected and why*.
- [ ] Split into **Directed** (I chose the approach: lock ordering, idempotency scope, hold model) vs **Decided** (AI proposed, I accepted: boilerplate, DTOs, config, burst-script scaffolding).
- [ ] Include at least one concrete example where AI output was wrong or unsafe (e.g. a read-then-write race) and how you caught it.
- [ ] Be able to explain every SQL statement and every lock in the interview without looking.

---

## 6. API Contract Summary

| Method & Path | Auth | Success | Notable failures |
|---------------|------|---------|------------------|
| `POST /auth/token` *(flagged)* | public | `200 { token }` | `404` when disabled (USER); `401` without bootstrap secret (ADMIN) |
| `POST /shows` | ADMIN | `201` show + seats | `400`, `401`, `403` |
| `GET /shows/{id}` | public/auth | `200` state + counts | `404` |
| `POST /shows/{id}/reserve` | USER | `201` new / `200` replay | `400`, `401`, `404`, `409` (`SEAT_TAKEN`, `PER_USER_LIMIT`, `IDEMPOTENCY_KEY_REUSED`), `429` |
| `POST /reservations/{id}/cancel` | USER (owner) | `200` cancelled | `401`, `403`, `404` |
| `GET /health/live` | public | `200` | — |
| `GET /health/ready` | public | `200` | `503` when DB down |
| `GET /metrics` | public | Prometheus text | — |

**Reserve success body**
```json
{
  "reservation_id": "b3c1…",
  "show_id": "9f2a…",
  "user_id": "alice",
  "seats": ["A12"],
  "amount_paise": 25000,
  "status": "confirmed"
}
```

---

## 7. Error Model

Uniform body for every non-2xx response:

```json
{
  "error": {
    "code": "SEAT_TAKEN",
    "message": "One or more requested seats are no longer available.",
    "details": { "seats": ["A12"] },
    "request_id": "7d1c9e1a-…"
  }
}
```

| HTTP | `code` | When |
|------|--------|------|
| 400 | `VALIDATION_ERROR` | Malformed JSON, missing key, empty seats, bad price |
| 400/404 | `INVALID_SEAT` | Seat label does not exist in the show |
| 401 | `UNAUTHENTICATED` | Missing/invalid/expired token |
| 403 | `FORBIDDEN` | Wrong role, or cancelling someone else's reservation |
| 404 | `NOT_FOUND` | Unknown show/reservation |
| 409 | `SEAT_TAKEN` | Any requested seat unavailable (all-or-nothing) |
| 409 | `PER_USER_LIMIT` | Would exceed `per_user_limit` |
| 409 | `IDEMPOTENCY_KEY_REUSED` | Same key, different body |
| 429 | `RETRY_LATER` | Transient contention exhausted retries (carries `Retry-After`) |
| 503 | — | **Only** `/health/ready` when DB is down |

---

## 8. Repository Layout

```
book-my-seat/
├── pom.xml
├── mvnw, mvnw.cmd, .mvn/
├── Dockerfile
├── docker-compose.yml
├── Makefile
├── burst.sh
├── burst/                         # Java 21 burst program (or k6 script)
├── README.md
├── WRITEUP.md
├── PROJECT.md                     # this document
├── .github/workflows/ci.yml
└── src/
    ├── main/
    │   ├── java/com/bookmyseat/
    │   │   ├── BookMySeatApplication.java
    │   │   ├── config/            # datasource, security, jackson, metrics
    │   │   ├── controller/        # all REST controllers
    │   │   ├── service/           # application services (show, reserve, auth, health)
    │   │   ├── entity/            # JPA entities (all tables)
    │   │   ├── repository/        # Spring Data repositories (native SQL on hot paths)
    │   │   ├── security/          # JWT filter, token service, auth controller
    │   │   ├── show/              # controller, service, DTOs
    │   │   ├── reservation/       # controller, service (atomic core), DTOs
    │   │   ├── idempotency/       # request hashing
    │   │   ├── observability/     # request-id filter, metrics binders, health indicators
    │   │   └── web/               # @RestControllerAdvice, error DTOs, exceptions
    │   └── resources/
    │       ├── application.properties
    │       ├── logback-spring.xml
    │       └── db/migration/V1__init.sql
    └── test/java/com/bookmyseat/    # integration + concurrency tests (Testcontainers)
```

---

## 9. Definition of Done (mapped to the grader's checks)

### 9.1 Correctness bar
| # | Grader check | Covered by | Done |
|---|--------------|-----------|------|
| 1 | Each stormed hot seat → exactly one `201`, all others `409` | G5, G8, G19 | [ ] |
| 2 | Zero 5xx across the whole burst | G11, G12 | [ ] |
| 3 | `available + held + confirmed == total_seats` during and after | G10, G14 | [ ] |
| 4 | Same key → one reservation; different seats on same key → `409` | G7 | [ ] |
| 5 | One user, 10 parallel reserves on limit 4 → ≤ 4 held | G6 | [ ] |
| 6 | Spoofed body identity ignored; only own holds cancellable | G3, G9 | [ ] |

### 9.2 Deploy & Observe
| Requirement | Covered by | Done |
|-------------|-----------|------|
| Public URL, survives cold start, comes up healthy | G17 | [ ] |
| Containerised; clean checkout runs like deploy | G16 | [ ] |
| Liveness + readiness that fails closed when DB is down | G13 | [ ] |
| Prometheus metrics: confirmed, declined-by-reason, seats available — reconciling with API | G14 | [ ] |
| Structured logs with request id + public access or recording | G15 | [ ] |
| One-command burst script with distribution + reconciliation | G18 | [ ] |

### 9.3 Deliverables
- [ ] Public Git repo with full, incremental commit history
- [ ] Live URL in README
- [ ] Burst script + run instructions in README
- [ ] Metrics + logs access documented
- [ ] `WRITEUP.md` with all required sections (incl. honest AI usage)
- [ ] Clean-clone build verified (`git clone` → `docker compose up --build` → green)

---

## 10. Suggested Timeline & Commit Plan

| Phase | Time | Work | Example commits |
|-------|------|------|-----------------|
| 1. Foundation | ~1h | Skeleton, wrapper, Compose, Flyway, schema | `chore: bootstrap spring boot + maven wrapper` · `feat: initial schema with constraints` |
| 2. Identity & shows | ~1h | JWT, roles, create/get show | `feat: jwt auth with admin/user roles` · `feat: create show with bulk seat insert` |
| 3. Atomic core | ~2h | Reserve with row locks + guarded update; error model | `feat: reserve seats atomically with ordered row locks` · `feat: global exception handling` |
| 4. Limits & idempotency | ~1.5h | Quota gate, idempotency table, replay semantics | `feat: per-user limit via conditional quota update` · `feat: idempotency keys with request hash` |
| 5. Cancel & invariants | ~1h | Cancel flow, snapshot-consistent show state | `feat: owner-only cancel without resurrecting seats` · `feat: single-snapshot show counts` |
| 6. Concurrency tests | ~1.5h | Testcontainers race tests | `test: hot seat race (500 threads)` · `test: overlapping multi-seat deadlock check` |
| 7. Observability | ~1.5h | Health, metrics, JSON logs, request id | `feat: liveness/readiness probes` · `feat: prometheus metrics` · `feat: structured logs with request id` |
| 8. Container & deploy | ~1h | Dockerfile, CI, deploy, cold-start check | `build: multi-stage dockerfile` · `chore: deploy config` |
| 9. Burst & tuning | ~1.5h | Burst program, pool/Tomcat tuning against live URL | `feat: one-command burst script` · `perf: tune hikari + virtual threads` |
| 10. Docs | ~1h | README, WRITEUP, AI log | `docs: readme, writeup and ai usage` |

---

## 11. Interview Readiness

You will be asked to **extend the service live**. Pre-think these likely extensions and where they plug in:

| Likely extension | Where it hooks in |
|------------------|-------------------|
| TTL-based holds with auto-expiry | Add `held` state + `hold_expires_at`; sweeper using `UPDATE … WHERE status='held' AND hold_expires_at < now()` guarded on `reservation_id`; confirm step guarded on non-expiry |
| Payment step (hold → pay → confirm) | Two-phase reserve: hold in G5, confirm endpoint with idempotency; compensate by releasing on failure |
| Best-effort multi-seat | Swap the all-or-nothing check for partial grant; quota increments by granted count |
| Rate limiting / waiting room | Filter in front of reserve; token bucket per user/IP returning `429` |
| Seat categories / dynamic pricing | `seats.category_id` + price lookup in the amount calculation |
| Many hot shows / sharding | Partition `seats` by `show_id`; per-show connection routing |

**Be ready to explain, without notes:** every lock and its order · why READ COMMITTED + `FOR UPDATE` is enough here · what the partial unique index protects against · why a decline doesn't consume an idempotency key · why the replay returns `200` not `201` · how readiness failing closed behaves on a DB partition.

---

## 12. Amendments & Follow-up Goals (G23+)

Work accepted after G22, recorded with the same rigour. Each amends the
sections above; the newest statement wins on conflict.

### G23 — Properties configuration
Same externalised config as G1, expressed in `application.properties`
(1:1 key mapping, env placeholders preserved). `application.yml` deleted;
all references updated.

### G24 — JPA repositories & entities
Data access moves from `JdbcTemplate` to Spring Data JPA repositories +
entities (spec §2 updated). Non-negotiable: Flyway still owns DDL
(`ddl-auto=validate`, `open-in-view=false`); contested paths stay native
`@Query` inside the repositories (sorted `FOR UPDATE` locks, guarded
conditional updates, jsonb bulk insert) because JPQL cannot express them.
Entities initialize every DB-defaulted column (e.g. `created_at`) in Java —
Hibernate inserts explicit `NULL`s that override column defaults. No
`@Version`: concurrency is governed by pessimistic locks, and managed
entities are never re-read after a native write in one transaction.

**Acceptance:** full suite green; concurrency ITs re-prove G5–G9 in CI.

### G25 — Package consolidation
`entity/` holds all entities, `repository/` all repositories,
`controller/` all controllers, `service/` all services (moved with history;
spec §8 layout synced). No behaviour change.

### G26 — Bootstrap admin issuance
`POST /auth/token` takes optional `role` (default `USER`; garbage → `400`).
`role=ADMIN` requires the bootstrap `ADMIN_TOKEN` secret as bearer
(constant-time compare; wrong/missing → `401`) and mints short-lived ADMIN
JWTs, independent of the dev-endpoint flag. Static-token auth still works
as fallback. Controller renamed `DevTokenController` → `AuthController`
(no environment words in names).

**Acceptance:** 8 slice tests (default/open/admin-ok/admin-wrong/
admin-despite-disabled/unknown-role/bad-user/disabled-404).

### G27 — Postman collection & brief
`book-my-seat.postman_collection.json` (17 requests, 5 folders, chained
variables incl. `adminJwt`, rerun-safe fixed keys) validated by parser
checks; `postman_brief.md` catalogues every request plus from-scratch
creation steps and troubleshooting.

### G28 — Rolling file log capture
Same JSON lines appended to `${LOG_PATH}/book-my-seat-api.log` (default
`target/logs/`, rolling 100MB/30d, git-ignored). Lesson recorded:
Logback's own parser needs `${VAR:-default}` — Spring-style `${VAR:default}`
resolves to a literal broken path and fails context startup.

### G29 — Live-burst hardening fixes
Bugs only a live burst could surface, each with the trace that convicted it:
entity timestamp defaults (G24 follow-up) and `saveAndFlush()` before the
guarded seat update (entity inserts defer to flush while native writes run
immediately — the FK safety net caught it as a loud `23503`, never silent
corruption).

**Acceptance:** `./burst.sh` against the live URL prints all-green.
