package com.bookmyseat.reservation;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.bookmyseat.common.TransactionRetry;
import com.bookmyseat.idempotency.RequestHasher;
import com.bookmyseat.observability.ReservationMetrics;
import com.bookmyseat.security.AuthPrincipal;
import com.bookmyseat.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Atomic reserve core (G5). One {@code READ COMMITTED} transaction:
 *
 * <ol>
 * <li>validate + normalise (trim, de-duplicate, <b>sort</b>);</li>
 * <li>idempotency gate (G7): same-transaction key insert; replays return the
 * stored reservation before touching quota or seats;</li>
 * <li>fast {@code per_user_limit} pre-check (the concurrent gate is G6);</li>
 * <li>conditional quota increment (G6) — serialises same-user parallels;</li>
 * <li>lock every requested seat with {@code SELECT … ORDER BY seat_label
 * FOR UPDATE} — concurrent transactions queue behind the first;</li>
 * <li>re-check status while holding the locks, then a guarded
 * {@code UPDATE … WHERE status='available'} (belt and braces);</li>
 * <li>insert the reservation + {@code reservation_seats} safety-net rows and
 * link the idempotency key.</li>
 * </ol>
 *
 * <p>Global lock order (G8): idempotency row (G7) → quota row (G6) →
 * seats sorted by label → reservation row. The decision is taken while
 * holding a row lock on every requested seat, so 500 racers on one seat
 * produce exactly one {@code 201}. Declines roll back cleanly as 4xx.
 *
 * <p>Multi-seat policy (G8) is all-or-nothing: any unavailable seat declines
 * the whole request, nothing is held and quota is untouched. Because every
 * request normalises to sorted order, overlapping pairs like
 * {@code [A1,A2]} and {@code [A2,A1]} lock identically and cannot deadlock;
 * residual transient contention (deadlock/serialization/lock-timeout) is
 * retried outside the transaction boundary, up to 3 attempts, then
 * {@code 429} — never a 5xx.
 *
 * <p>Cancel (G9) follows the same order — quota → seats sorted →
 * reservation — and frees seats only while tied to the cancelled
 * reservation, so re-booked seats are never resurrected.
 */
@Service
public class ReservationService {

	private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final TransactionRetry retry;
	private final ReservationMetrics metrics;

	public ReservationService(JdbcTemplate jdbc, PlatformTransactionManager txManager, ReservationMetrics metrics) {
		this.jdbc = jdbc;
		this.tx = new TransactionTemplate(txManager);
		this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.metrics = metrics;
		this.retry = new TransactionRetry(3, 25, metrics::retried);
	}

	public ReserveOutcome reserve(UUID showId, List<String> seats, JsonNode bodyKey, String headerKey,
			AuthPrincipal caller) {
		String keyPreview = previewKey(bodyKey, headerKey);
		decisionMdc(showId, caller.userId(), keyPreview);
		try {
			ReserveOutcome outcome = retry
					.run(() -> tx.execute(status -> reserveTx(showId, seats, bodyKey, headerKey, caller)));
			if (outcome.replayed()) {
				metrics.replayed();
				log.info("reservation.replayed seats={}", outcome.response().seats().size());
			}
			else {
				metrics.confirmed();
				log.info("reservation.confirmed reservation_id={} seats={} amount_paise={}",
						outcome.response().reservationId(), outcome.response().seats().size(),
						outcome.response().amountPaise());
			}
			return outcome;
		}
		catch (ApiException e) {
			metrics.declined(e.getCode());
			log.info("reservation.declined reason={}", e.getCode());
			throw e;
		}
		finally {
			clearDecisionMdc();
		}
	}

	private ReserveOutcome reserveTx(UUID showId, List<String> seats, JsonNode bodyKey, String headerKey,
			AuthPrincipal caller) {
		String idempotencyKey = ReserveRequestValidator.resolveKey(bodyKey, headerKey);
		List<String> labels = ReserveRequestValidator.normalizeSeats(seats);
		String requestHash = RequestHasher.hash(showId, labels);

		// Idempotency gate (G7): first statement. A concurrent same-key
		// transaction blocks on this row until the first commits or aborts, so
		// there is no window for a double reserve. A declined first attempt
		// rolls back and frees the key.
		int keyInserted = jdbc.update(
				"INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash) VALUES (?,?,?) "
						+ "ON CONFLICT (user_id, idempotency_key) DO NOTHING",
				caller.userId(), idempotencyKey, requestHash);
		if (keyInserted == 0) {
			return replay(caller.userId(), idempotencyKey, requestHash);
		}

		long pricePaise;
		int perUserLimit;
		try {
			Map<String, Object> show = jdbc.queryForMap(
					"SELECT price_paise, per_user_limit FROM shows WHERE id = ?", showId);
			pricePaise = ((Number) show.get("price_paise")).longValue();
			perUserLimit = ((Number) show.get("per_user_limit")).intValue();
		}
		catch (EmptyResultDataAccessException e) {
			throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Unknown show.");
		}

		if (labels.size() > perUserLimit) {
			throw new ApiException(HttpStatus.CONFLICT, "PER_USER_LIMIT",
					"Request exceeds per_user_limit of " + perUserLimit + ".");
		}
		// Quota gate (G6): the conditional UPDATE takes the quota row lock,
		// serialising parallel requests from the same user. It sits before the
		// seat locks per the global order (quota → seats) and rolls back with
		// the transaction when the seat step declines, so failed attempts
		// consume nothing. Idempotent replays (G7) return before this point
		// and never increment again.
		jdbc.update(
				"INSERT INTO user_show_quota (user_id, show_id, active_seats) VALUES (?,?,0) "
						+ "ON CONFLICT (user_id, show_id) DO NOTHING",
				caller.userId(), showId);
		int quotaAffected = jdbc.update(
				"UPDATE user_show_quota SET active_seats = active_seats + ? "
						+ "WHERE user_id = ? AND show_id = ? AND active_seats + ? <= ?",
				labels.size(), caller.userId(), showId, labels.size(), perUserLimit);
		if (quotaAffected == 0) {
			throw new ApiException(HttpStatus.CONFLICT, "PER_USER_LIMIT",
					"Would exceed per_user_limit of " + perUserLimit + ".");
		}

		List<SeatRow> locked = jdbc.query(con -> {
			PreparedStatement ps = con.prepareStatement(
					"SELECT seat_label, status FROM seats WHERE show_id = ? AND seat_label = ANY(?) "
							+ "ORDER BY seat_label FOR UPDATE");
			ps.setObject(1, showId);
			ps.setArray(2, con.createArrayOf("text", labels.toArray(new String[0])));
			return ps;
		}, (rs, i) -> new SeatRow(rs.getString(1), rs.getString(2)));

		if (locked.size() != labels.size()) {
			var found = locked.stream().map(SeatRow::label).toList();
			var missing = labels.stream().filter(l -> !found.contains(l)).toList();
			throw new ApiException(HttpStatus.NOT_FOUND, "INVALID_SEAT",
					"Unknown seats for this show.", Map.of("seats", missing));
		}
		var taken = locked.stream().filter(r -> !"available".equals(r.status())).map(SeatRow::label).toList();
		if (!taken.isEmpty()) {
			throw new ApiException(HttpStatus.CONFLICT, "SEAT_TAKEN",
					"One or more requested seats are no longer available.", Map.of("seats", taken));
		}

		long amount;
		try {
			amount = Math.multiplyExact(pricePaise, labels.size());
		}
		catch (ArithmeticException e) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "amount_paise overflows.");
		}

		UUID reservationId = UUID.randomUUID();
		int affected = jdbc.update(con -> {
			PreparedStatement ps = con.prepareStatement(
					"UPDATE seats SET status='confirmed', reservation_id=?, user_id=?, updated_at=now() "
							+ "WHERE show_id=? AND seat_label = ANY(?) AND status='available'");
			ps.setObject(1, reservationId);
			ps.setString(2, caller.userId());
			ps.setObject(3, showId);
			ps.setArray(4, con.createArrayOf("text", labels.toArray(new String[0])));
			return ps;
		});
		if (affected != labels.size()) {
			throw new ApiException(HttpStatus.CONFLICT, "SEAT_TAKEN",
					"One or more requested seats are no longer available.", Map.of("seats", labels));
		}

		jdbc.update(
				"INSERT INTO reservations (id, show_id, user_id, status, amount_paise) VALUES (?,?,?,?,?)",
				reservationId, showId, caller.userId(), "confirmed", amount);
		jdbc.batchUpdate(
				"INSERT INTO reservation_seats (reservation_id, show_id, seat_label, active) VALUES (?,?,?,TRUE)",
				labels, labels.size(), (ps, label) -> {
					ps.setObject(1, reservationId);
					ps.setObject(2, showId);
					ps.setString(3, label);
				});
		jdbc.update("UPDATE idempotency_keys SET reservation_id = ? WHERE user_id = ? AND idempotency_key = ?",
				reservationId, caller.userId(), idempotencyKey);

		return new ReserveOutcome(new ReserveResponse(reservationId, showId, caller.userId(),
				new ArrayList<>(labels), amount, "confirmed"), false);
	}

	/**
	 * Same key seen before: mismatched fingerprint → 409, otherwise the stored
	 * reservation with no seat, quota or counter movement.
	 */
	private ReserveOutcome replay(String userId, String idempotencyKey, String requestHash) {
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT request_hash, reservation_id FROM idempotency_keys WHERE user_id = ? AND idempotency_key = ?",
				userId, idempotencyKey);
		if (rows.isEmpty()) {
			// Defensive: the conflicting row vanished (first attempt aborted
			// after we observed the conflict). Re-insert and proceed as first-timer.
			jdbc.update(
					"INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash) VALUES (?,?,?) "
							+ "ON CONFLICT (user_id, idempotency_key) DO NOTHING",
					userId, idempotencyKey, requestHash);
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RETRY_LATER",
					"Concurrent reservation in progress; retry with the same key.");
		}
		String storedHash = (String) rows.get(0).get("request_hash");
		if (!requestHash.equals(storedHash)) {
			throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
					"Idempotency key was already used with a different request.");
		}
		UUID reservationId = (UUID) rows.get(0).get("reservation_id");
		if (reservationId == null) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RETRY_LATER",
					"Concurrent reservation in progress; retry with the same key.");
		}
		Map<String, Object> reservation;
		try {
			reservation = jdbc.queryForMap(
					"SELECT show_id, user_id, amount_paise, status FROM reservations WHERE id = ?", reservationId);
		}
		catch (EmptyResultDataAccessException e) {
			throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Original reservation is gone.");
		}
		List<String> seats = jdbc.queryForList(
				"SELECT seat_label FROM reservation_seats WHERE reservation_id = ? ORDER BY seat_label",
				String.class, reservationId);
		// TODO(G14): increment the idempotent-replay counter only (never confirmed).
		return new ReserveOutcome(
				new ReserveResponse(reservationId, (UUID) reservation.get("show_id"),
						(String) reservation.get("user_id"), seats,
						((Number) reservation.get("amount_paise")).longValue(), (String) reservation.get("status")),
				true);
	}

	/**
	 * Owner-only cancel (G9). Follows the global lock order — quota →
	 * seats (sorted) → reservation — and releases seats only while they are
	 * still tied to <b>this</b> reservation, so a late duplicate cancel can
	 * never free a seat that was re-booked by someone else. Repeat cancel is
	 * {@code 200} with no double decrement.
	 */
	public CancelResponse cancel(UUID reservationId, AuthPrincipal caller) {
		decisionMdc(null, caller.userId(), null);
		try {
			for (int i = 0; i < 3; i++) {
				try {
					CancelResponse response = retry.run(() -> tx.execute(status -> cancelTx(reservationId, caller)));
					metrics.cancelled();
					metrics.released(response.seats().size());
					log.info("reservation.cancelled reservation_id={} seats={}", reservationId,
							response.seats().size());
					return response;
				}
				catch (CancelConflictException e) {
					// Lost a concurrent-cancel race after touching quota; the whole
					// attempt rolled back, so re-reading is safe (next pass sees
					// cancelled → idempotent 200).
				}
				catch (ApiException e) {
					metrics.declined(e.getCode());
					log.info("reservation.declined reason={}", e.getCode());
					throw e;
				}
			}
			return readCancelState(reservationId, caller);
		}
		finally {
			clearDecisionMdc();
		}
	}

	private CancelResponse cancelTx(UUID reservationId, AuthPrincipal caller) {
		Map<String, Object> reservation;
		try {
			reservation = jdbc.queryForMap(
					"SELECT show_id, user_id, status FROM reservations WHERE id = ?", reservationId);
		}
		catch (EmptyResultDataAccessException e) {
			throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Unknown reservation.");
		}
		UUID showId = (UUID) reservation.get("show_id");
		String owner = (String) reservation.get("user_id");
		if (!caller.userId().equals(owner)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the owner can cancel.");
		}
		if ("cancelled".equals(reservation.get("status"))) {
			return cancelState(reservationId, showId, owner);
		}

		List<String> labels = jdbc.queryForList(
				"SELECT seat_label FROM seats WHERE reservation_id = ? ORDER BY seat_label", String.class,
				reservationId);

		jdbc.update("UPDATE user_show_quota SET active_seats = active_seats - ? WHERE user_id = ? AND show_id = ?",
				labels.size(), owner, showId);

		List<String> locked = jdbc.query(con -> {
			PreparedStatement ps = con.prepareStatement(
					"SELECT seat_label FROM seats WHERE reservation_id = ? ORDER BY seat_label FOR UPDATE");
			ps.setObject(1, reservationId);
			return ps;
		}, (rs, i) -> rs.getString(1));

		int transitioned = jdbc.update(
				"UPDATE reservations SET status='cancelled', cancelled_at=now() "
						+ "WHERE id=? AND user_id=? AND status='confirmed'",
				reservationId, owner);
		if (transitioned == 0) {
			// A concurrent cancel committed first; quota/seat moves above roll
			// back with this attempt — retry to return the idempotent 200.
			throw new CancelConflictException();
		}

		// Guarded by reservation_id: seats re-booked to a new owner since are untouched.
		jdbc.update(con -> {
			PreparedStatement ps = con.prepareStatement(
					"UPDATE seats SET status='available', reservation_id=NULL, user_id=NULL, updated_at=now() "
							+ "WHERE reservation_id=? AND status IN ('held','confirmed')");
			ps.setObject(1, reservationId);
			return ps;
		});
		jdbc.update("UPDATE reservation_seats SET active=FALSE WHERE reservation_id=? AND active", reservationId);
		// TODO(G14): increment reservations_cancelled_total and seats_released_total.

		return new CancelResponse(reservationId, showId, owner, locked, "cancelled");
	}

	private CancelResponse readCancelState(UUID reservationId, AuthPrincipal caller) {
		Map<String, Object> reservation;
		try {
			reservation = jdbc.queryForMap(
					"SELECT show_id, user_id, status FROM reservations WHERE id = ?", reservationId);
		}
		catch (EmptyResultDataAccessException e) {
			throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Unknown reservation.");
		}
		if (!caller.userId().equals(reservation.get("user_id"))) {
			throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the owner can cancel.");
		}
		return cancelState(reservationId, (UUID) reservation.get("show_id"), (String) reservation.get("user_id"));
	}

	private CancelResponse cancelState(UUID reservationId, UUID showId, String owner) {
		List<String> seats = jdbc.queryForList(
				"SELECT seat_label FROM reservation_seats WHERE reservation_id = ? ORDER BY seat_label",
				String.class, reservationId);
		return new CancelResponse(reservationId, showId, owner, seats, "cancelled");
	}

	/** Internal: lost a concurrent-cancel race; the attempt rolled back — re-read instead. */
	private static final class CancelConflictException extends RuntimeException {
	}

	/**
	 * Per-decision MDC (G15): {@code show_id} and a truncated key preview join
	 * the {@code request_id}/{@code user_id} set upstream, so any line is
	 * greppable from the error response. Removed afterwards — threads are reused.
	 */
	private static void decisionMdc(UUID showId, String userId, String keyPreview) {
		if (showId != null) {
			MDC.put("show_id", showId.toString());
		}
		if (userId != null) {
			MDC.put("user_id", userId);
		}
		if (keyPreview != null) {
			MDC.put("idempotency_key", keyPreview);
		}
	}

	private static void clearDecisionMdc() {
		MDC.remove("show_id");
		MDC.remove("idempotency_key");
		MDC.remove("outcome");
	}

	private static String previewKey(JsonNode bodyKey, String headerKey) {
		String raw = headerKey != null && !headerKey.isBlank() ? headerKey.trim()
				: bodyKey != null && bodyKey.isTextual() ? bodyKey.textValue().trim() : null;
		if (raw == null || raw.isEmpty()) {
			return null;
		}
		return raw.length() <= 12 ? raw : raw.substring(0, 12);
	}

	private record SeatRow(String label, String status) {
	}
}
