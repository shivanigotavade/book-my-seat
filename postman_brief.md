# Postman Brief — Book My Seat

Collection file: [`book-my-seat.postman_collection.json`](book-my-seat.postman_collection.json)
(Postman v2.1 format — import it, no hand-building needed).

## Import and run

1. Postman → **Import** → select `book-my-seat.postman_collection.json`.
2. Check the collection **Variables**: `baseUrl` defaults to
   `http://localhost:8080` (live: `https://book-my-seat-7pdy.onrender.com`); `adminToken` defaults to `dev-admin-token`
   (must match the server's `ADMIN_TOKEN`). `adminJwt` is filled by the
   bootstrap request below. For the live deployment, change only `baseUrl`
   (and `adminToken` to the prod secret).
3. Run folders top to bottom (or the whole collection with Runner). Every
   request's **Tests** script asserts its contract and chains variables:
   tokens → `userToken`/`userToken2`, show creation → `showId`, reserve →
   `reservationId`.

## Request catalogue

| # | Request | Auth | Body | Expect |
|---|---------|------|------|--------|
| 1 | `POST /auth/token` (alice) | public | `{"user_id":"alice"}` | `200` + JWT → `userToken` |
| 2 | `POST /auth/token` (bob) | public | `{"user_id":"bob"}` | `200` → `userToken2` |
| 2b | `POST /auth/token` (admin bootstrap) | bootstrap secret bearer | `{"user_id":"ops","role":"ADMIN"}` | `200` ADMIN JWT → `adminJwt`; wrong secret → `401` |
| 3 | `POST /shows` | admin bearer | name + `["A1".."A5"]` + `price_paise` | `201` → `showId` |
| 4 | `POST /shows` as USER | user bearer | same shape | `403 FORBIDDEN` |
| 4b | `POST /shows` as ADMIN JWT | `{{adminJwt}}` bearer | same shape, fresh show | `201`, moves `showId` to the new show |
| 5 | `GET /shows/{id}` | none | — | `200`, counts sum to total |
| 6 | `GET /shows/{id}?summary=true` | none | — | `200`, no `seats` key |
| 7 | `POST /shows/{id}/reserve` | alice | `A1`, key `postman-1` | `201`, owner alice → `reservationId` |
| 8 | replay same key | alice | identical body | `200` + `Idempotent-Replayed: true`, same id |
| 9 | same key, `A2` | alice | different seats | `409 IDEMPOTENCY_KEY_REUSED` |
| 10 | `A1` as bob | bob bearer | fresh key | `409 SEAT_TAKEN` |
| 11 | `POST /reservations/{id}/cancel` | bob | — | `403` (not owner) |
| 12 | same cancel | alice | — | `200`, status `cancelled` |
| 13 | reserve `A1` as bob | bob bearer | fresh key | `201`, owner bob |
| 14 | `GET /health/live` | none | — | `200 UP` |
| 15 | `GET /health/ready` | none | — | `200 UP/UP` |
| 16 | `GET /metrics` | none | — | `200` Prometheus text |

Requests 7–13 are rerun-safe: keys are fixed, so a second full run replays
(`200`) instead of double-booking; to start over, re-run request 3 for a
fresh `showId` (all later requests follow it automatically).

## Admin tokens (prod-ready bootstrap)

There is no open admin-minting endpoint — that would be privilege escalation
for anyone. Instead: send the server's `ADMIN_TOKEN` secret once as
`Authorization: Bearer {{adminToken}}` to `POST /auth/token` with
`{"user_id":"ops","role":"ADMIN"}` and you get a short-lived ADMIN JWT
(stored in `adminJwt`). Use that JWT on admin routes, or keep using the
static secret directly — both authenticate. Rotate the server secret on
suspicion and keep JWT expiry short.

## Creating requests from scratch (no import)

1. **Collections → New → blank**, name it `Book My Seat`.
2. **Variables tab**: add `baseUrl`, `adminToken`, `userToken`,
   `userToken2`, `showId`, `reservationId` (leave token/id values empty —
   scripts fill them).
3. **Add request**: name it, set method + URL using `{{baseUrl}}` and other
   variables, e.g. `{{baseUrl}}/shows/{{showId}}/reserve`.
4. **Authorization tab**: type **Bearer Token**, value `{{userToken}}`
   (or `{{adminToken}}` for admin calls). Public endpoints: **No Auth**.
5. **Body tab** (POST): **raw + JSON**, e.g.
   `{"seats":["A1"],"idempotency_key":"postman-1"}`. Never add `user_id` —
   identity comes from the token; the API ignores body identity fields.
6. **Tests tab**: assert status and contract, then chain, e.g.
   `pm.collectionVariables.set("showId", pm.response.json().id);`
7. Order matters: Auth → Shows → Reserve → Cancel → Observe, because each
   folder consumes variables set by the previous one.

## Troubleshooting

- `401 UNAUTHENTICATED`: token variable empty — run the Auth folder first.
- `404 NOT_FOUND` on show/reservation: `showId`/`reservationId` stale —
  re-run request 3 (and 7).
- Re-book returns `409`: a previous partial run left `A1` confirmed to
  someone else — re-run request 3 for a fresh show.
- `X-Request-Id` on every response matches the JSON server logs for that
  call — include it when reporting failures.
