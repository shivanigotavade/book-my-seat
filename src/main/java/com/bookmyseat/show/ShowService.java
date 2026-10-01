package com.bookmyseat.show;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Show creation (G4). One transaction: insert the show row, then bulk-insert
 * every seat as {@code available} via a single {@code unnest} round-trip so
 * even 50k-seat shows create quickly.
 */
@Service
public class ShowService {

	private final JdbcTemplate jdbc;

	public ShowService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
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
}
