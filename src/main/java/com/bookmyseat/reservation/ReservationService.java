package com.bookmyseat.reservation;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bookmyseat.security.AuthPrincipal;
import com.bookmyseat.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Atomic reserve core (G5). One {@code READ COMMITTED} transaction:
 *
 * <ol>
 * <li>validate + normalise (trim, de-duplicate, <b>sort</b>);</li>
 * <li>fast {@code per_user_limit} pre-check (the concurrent gate is G6);</li>
 * <li>lock every requested seat with {@code SELECT … ORDER BY seat_label
 * FOR UPDATE} — concurrent transactions queue behind the first;</li>
 * <li>re-check status while holding the locks, then a guarded
 * {@code UPDATE … WHERE status='available'} (belt and braces);</li>
 * <li>insert the reservation + {@code reservation_seats} safety-net rows.</li>
 * </ol>
 *
 * <p>Global lock order (G8): idempotency row (G7) → quota row (G6) →
 * seats sorted by label → reservation row. The decision is taken while
 * holding a row lock on every requested seat, so 500 racers on one seat
 * produce exactly one {@code 201}. Declines roll back cleanly as 4xx.
 */
@Service
public class ReservationService {

	private final JdbcTemplate jdbc;

	public ReservationService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional
	public ReserveResponse reserve(UUID showId, List<String> seats, JsonNode bodyKey, String headerKey,
			AuthPrincipal caller) {
		// Required from G5 on; persisted by the G7 gate (first statement there).
		ReserveRequestValidator.resolveKey(bodyKey, headerKey);
		List<String> labels = ReserveRequestValidator.normalizeSeats(seats);

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
		// TODO(G7): idempotency gate goes first here — same-transaction key
		// insert on (user_id, key); a concurrent same-key transaction blocks on
		// that row until the first commits/aborts, and replays return the
		// stored reservation before touching quota or seats.
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

		return new ReserveResponse(reservationId, showId, caller.userId(), new ArrayList<>(labels), amount,
				"confirmed");
	}

	private record SeatRow(String label, String status) {
	}
}
