package com.bookmyseat.show;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.bookmyseat.web.ApiException;

/**
 * Show creation (G4). One transaction: insert the show row, then bulk-insert
 * every seat as {@code available} via a single {@code unnest} round-trip so
 * even 50k-seat shows create quickly.
 */
@Service
public class ShowService {

	private final JdbcTemplate jdbc;
	private final TransactionTemplate readSnapshot;

	public ShowService(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
		this.jdbc = jdbc;
		this.readSnapshot = new TransactionTemplate(txManager);
		this.readSnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
		this.readSnapshot.setReadOnly(true);
	}

	@Transactional
	public ShowResponse createShow(CreateShowRequest request) {
		var validated = ShowRequestValidator.validate(request == null ? null : request.name(),
				request == null ? null : request.seats(), request == null ? null : request.pricePaise(),
				request == null ? null : request.perUserLimit());

		UUID showId = UUID.randomUUID();
		jdbc.update(
				"INSERT INTO shows (id, name, price_paise, per_user_limit, total_seats) VALUES (?,?,?,?,?)",
				showId, validated.name(), validated.pricePaise(), validated.perUserLimit(),
				validated.labels().size());

		List<String> labels = validated.labels();
		jdbc.update(con -> {
			PreparedStatement ps = con.prepareStatement(
					"INSERT INTO seats (show_id, seat_label, status) SELECT ?, unnest(CAST(? AS text[])), 'available'");
			ps.setObject(1, showId);
			Array array = con.createArrayOf("text", labels.toArray(new String[0]));
			ps.setArray(2, array);
			return ps;
		});

		List<ShowResponse.SeatItem> items = labels.stream()
				.map(label -> new ShowResponse.SeatItem(label, "available")).toList();
		return new ShowResponse(showId, validated.name(), validated.pricePaise(), validated.perUserLimit(),
				labels.size(), items);
	}

	/**
	 * Show state with the reconciliation invariant (G10). Header, counts and
	 * seat list are read in one {@code REPEATABLE READ} read-only transaction
	 * so they describe the same snapshot — polling mid-burst never observes a
	 * sum different from {@code total_seats}.
	 */
	public ShowDetailResponse getShow(UUID showId, boolean summary) {
		return readSnapshot.execute(status -> {
			Map<String, Object> show;
			try {
				show = jdbc.queryForMap(
						"SELECT name, price_paise, per_user_limit, total_seats FROM shows WHERE id = ?", showId);
			}
			catch (EmptyResultDataAccessException e) {
				throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Unknown show.");
			}
			Map<String, Long> counts = new HashMap<>(Map.of("available", 0L, "held", 0L, "confirmed", 0L));
			jdbc.query("SELECT status, COUNT(*) AS n FROM seats WHERE show_id = ? GROUP BY status", rs -> {
				counts.put(rs.getString(1), rs.getLong(2));
			}, showId);
			List<ShowResponse.SeatItem> seats = summary ? null
					: jdbc.query("SELECT seat_label, status FROM seats WHERE show_id = ? ORDER BY seat_label",
							(rs, i) -> new ShowResponse.SeatItem(rs.getString(1), rs.getString(2)), showId);
			return new ShowDetailResponse(showId, (String) show.get("name"),
					((Number) show.get("price_paise")).longValue(), ((Number) show.get("per_user_limit")).intValue(),
					((Number) show.get("total_seats")).intValue(), new ShowDetailResponse.Counts(
							counts.get("available"), counts.get("held"), counts.get("confirmed")),
					seats);
		});
	}
}
