# Book My Seat — Write-up

## The atomic decision

The reserve path (`ReservationService.reserveTx`) runs one `READ COMMITTED`
transaction with a fixed global lock order:

1. idempotency row — `INSERT INTO idempotency_keys … ON CONFLICT DO NOTHING`
2. quota row — lazy upsert, then conditional
   `UPDATE user_show_quota SET active_seats = active_seats + :n WHERE … active_seats + :n <= :limit`
3. seats, sorted — `SELECT seat_label, status FROM seats WHERE show_id = ?
   AND seat_label = ANY(?) ORDER BY seat_label FOR UPDATE`
4. reservation row — plain insert (no contention on a fresh UUID)

It is race-free because the decision is taken **while holding a row lock on
every requested seat** and the state check is re-evaluated after the locks
are acquired: 500 transactions on `A12` queue behind the first; the winner
commits `confirmed`; each follower acquires the lock, sees `confirmed`, and
declines with `409 SEAT_TAKEN`. A guarded
`UPDATE seats … WHERE status='available'` (affected-rows must equal the
request size) re-checks at write time — belt and braces. The partial unique
index `ON reservation_seats (show_id, seat_label) WHERE active` makes a
double-hold impossible even if this logic had a bug.

Deadlock freedom for multi-seat: every request normalises labels to sorted
order first, so `["A1","A2"]` and `["A2","A1"]` lock identically. Residual
transient contention (`40P01` deadlock, `40001` serialization, `55P03` lock
timeout, pool acquisition) retries up to 3× with jittered backoff **outside**
the transaction boundary (`common/TransactionRetry`), then `429
RETRY_LATER` — never a 5xx. Session `lock_timeout 8s` / `statement_timeout
25s` bound worst-case waits.

There is intentionally no read-then-write on the hot path: the only
pre-lock read is the immutable show row (price, limit); everything contested
is decided under lock.

## Idempotency

The key (body `idempotency_key` or `Idempotency-Key` header; differing both
→ `400`) is scoped per user **and show** with
`UNIQUE (user_id, show_id, idempotency_key)` (re-keyed in G30: global scope
turned every rerun on a fresh show into a false reuse conflict) and written
**in the same transaction** as the reservation — exactly-once is
enforced by the database, not app memory. The fingerprint is
`SHA-256(show_id | sorted seats)` (`idempotency/RequestHasher`).

- First sight: key row inserts, flow continues; `reservation_id` is linked
  onto the key before commit.
- Same key + same hash: the stored reservation returns with `200` +
  `Idempotent-Replayed: true` **before** quota/seats are touched — no counter
  movement, and exactly one `201` per hot seat even when the winner retries
  (hence `200`, not a second `201`).
- Same key + different hash: `409 IDEMPOTENCY_KEY_REUSED`.
- A declined first attempt rolls back the key row with it, so the key stays
  reusable; a concurrent same-key transaction blocks on the key row until the
  first commits/aborts, so there is no double-reserve window.

## Holds & expiry

Explicit-cancel model: reserve confirms immediately (`confirmed`), and
`POST /reservations/{id}/cancel` is the only release path. Chosen over TTL
expiry to avoid a sweeper racing confirmations (a sweeper freeing a seat
mid-payment is a whole failure class deleted). Cancel follows the same lock
order (quota → seats sorted → reservation), uses guarded transitions on
**both** sides — `UPDATE reservations … WHERE status='confirmed'` and
`UPDATE seats … WHERE reservation_id = ? AND status IN ('held','confirmed')`
— so a late duplicate cancel can never free a seat re-booked to a new owner
(the `reservation_id` no longer matches). Repeat cancel is idempotent `200`
with no double decrement; a lost concurrent-cancel race rolls back fully and
re-reads into the same `200`. Quota counts `held + confirmed` and decrements
on cancel.

## Consistency vs availability under a partition

Single Postgres primary is the source of truth: we choose consistency and
fail closed. If the database is unreachable or wedged, `/health/ready`
returns `503` (2s-bounded `SELECT 1`) so orchestrators and reviewers stop
sending traffic instead of double-selling; business endpoints shed
contention as `429`, never guess. During failover writes block or fail and
surface as `429`/`503`, then recover without restarts — no split-brain
reservation state, at the cost of refusing bookings while partitioned. Read
replicas (future) would serve only show-state reads, never the decision.

## Observability — what pages at 2am

- **Readiness failing** (`/health/ready` ≠ 200): DB down or wedged — page.
- **Any 5xx on business endpoints**: must be zero; the catch-all 500 means
  an unknown bug, not contention.
- **`429` rate spike / `bookmyseat_tx_retries_total{cause}` spike**: contention
  beyond design (deadlock/serialization/lock-timeout) — scale pool or shed load.
- **p99 latency** (`http_server_requests_seconds`): queueing behind row locks
  or pool exhaustion.
- **Hikari saturation** (`hikaricp_connections_pending`): raise pool (within
  the PG cap) or add the bulkhead noted below.
- **Invariant drift**: `seats_available` gauge vs `GET /shows/{id}` counts,
  or `confirmed_total` vs client `201`s — indicates a counting bug, page.
- **`reservations_confirmed` rate anomalies**: drop to zero during on-sale =
  upstream or DB stall.

Every response carries `X-Request-Id` (accepted inbound or generated),
echoed in error bodies and JSON logs (`request_id,user_id,show_id,
idempotency_key` truncated, `outcome`) with one line per decision
(`reservation.confirmed/declined/replayed/cancelled`). Given a `request_id`,
one log search finds the decision. Logs: stdout JSON (platform log view in
prod).

## What I'd do next

TTL holds + expiry sweeper (guarded on `reservation_id`, confirm guarded on
non-expiry) · payment step as hold → pay → confirm saga with compensation ·
per-user/IP rate limiting + virtual waiting room · seat categories / dynamic
pricing in the amount calculation · sharded hot shows and Redis pre-filter ·
read replicas for show state · outbox events for downstream consumers.

## Performance notes (G31)

Same guarantees, fewer round trips per reserve: immutable show config is
cached in memory (no invalidation needed); quota upsert + conditional
increment merged into one `INSERT … ON CONFLICT DO UPDATE … WHERE fits`
(zero rows ⇒ `409`); Hibernate JDBC batching for multi-row writes; pool
sizing left env-driven per environment. Lock ordering, retry policy and
logging deliberately untouched. Burst client meters concurrency
(`STAMPEDE/STORM/SETUP_CONCURRENCY`) and retries transport failures on
idempotent-safe paths, so a 20k run measures sustained pressure, not socket
pile-on.

## AI usage (G22 disclosure log)

Built with an agentic coding assistant (OpenCode, Muse Spark) under
per-goal direction. Honest split:

**Directed — I chose, the agent executed.** Flyway over
`ddl-auto=create` (I initially picked `ddl-auto`, then reversed after the
agent laid out the costs: no partial-index/`CHECK` support, data loss on
restart, DoD failure); goal sequencing G1→G22; the brief's locked policies
(all-or-nothing, explicit cancel, replay-`200`, consistency-over-availability);
Render as deploy target; when to push.

**Decided — the agent proposed, I accepted.** `JdbcTemplate` over JPA on
all paths; `SHA-256(show_id | sorted seats)` fingerprint format;
conditional-update quota SQL; programmatic `TransactionTemplate` + retry
helper instead of `@Transactional`/`spring-retry`; global `SNAKE_CASE`;
aggregate (label-free) gauges; `set_config` session-timeout init SQL;
`EnvironmentPostProcessor` URL normalization instead of a hand-built
`DataSource` bean; Java single-file burst over k6.

**Rejected or fixed in review (with evidence).**
- `ddl-auto=create`: rejected post-discussion (see above).
- `@Valid` on `DevTokenController` produced Boot-default 400 bodies
  instead of the uniform `ApiError` — caught by
  `DevTokenControllerTest.invalidUserIdIs400`, replaced with manual
  validation (`fix` visible in history).
- `@WebMvcTest` silently falling back to default security (all-POST 403
  with empty bodies) — caught by `ShowControllerTest`, fixed with explicit
  `@Import(SecurityConfig.class)`.
- Read-then-write audit (the unsafe pattern from the brief): verified the
  only pre-lock read is the immutable show row; every contested read holds
  `FOR UPDATE` locks. The cancel path had a real instance of the cousin
  bug — concurrent double-cancel double-decrementing quota — identified
  while writing it and fixed with guarded-transition + rollback-and-reread
  before any test ran (`concurrentDoubleCancelIsIdempotent` now proves it).
- Burst `code()` helper mis-parsing the nested `error` object (would have
  broken every 409 assertion) — caught in self-review pre-compile; plus
  `CannotGetJdbcConnectionException` (spring-jdbc, not spring-dao) and the
  Micrometer 1.16 `prometheusmetrics` rename — caught by compiler/tests.

I can extend this live: every lock's order is documented in
`ReservationService`'s javadoc, and each guarantee has a named
Testcontainers test (`ReserveServiceIT`, `SchemaInvariantIT`,
`ShowServiceIT`) runnable via `./mvnw verify`.

**Addendum — JPA migration (post-G22, user-directed).** The user asked to
replace `JdbcTemplate` with JPA repositories + entities and update the spec
accordingly (§2 table now says so; Flyway still owns DDL with
`ddl-auto=validate`). The agent's constraint, accepted: JPQL cannot express
sorted `FOR UPDATE` locking, guarded conditional updates, or bulk insert, so
contested paths remain native `@Query` *inside* the repositories
(`lockSeats`, `confirmSeats`, quota/idempotency gates, jsonb bulk insert) —
the atomicity argument above is unchanged, only the plumbing moved from
`JdbcTemplate` to repositories. Deliberately not `@Version`: concurrency is
governed by pessimistic locks, and managed entities are never re-read after
a native write in the same transaction. Concurrency proofs re-run unchanged
in CI (`./mvnw verify`).
