package com.bookmyseat;

import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.assertj.core.api.Assertions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * G2 acceptance: Flyway applies cleanly on an empty DB and the database
 * itself rejects a double-confirm even if application logic is wrong.
 *
 * <p>Runs against real PostgreSQL 16 via Testcontainers. Skipped when Docker
 * is unavailable (local sandbox); CI with Docker executes it.
 */
class SchemaInvariantIT {

	private static boolean dockerAvailable() {
		try {
			return DockerClientFactory.instance().isDockerAvailable();
		} catch (Exception e) {
			return false;
		}
	}

	@Test
	void flywayAppliesAndConstraintsRejectDoubleSell() throws Exception {
		Assumptions.assumeTrue(dockerAvailable(), "Docker unavailable — skipping (CI runs this)");

		try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
			pg.start();

			Flyway flyway = Flyway.configure()
					.dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
					.locations("classpath:db/migration")
					.load();
			var result = flyway.migrate();
			Assertions.assertThat(result.success).isTrue();
			Assertions.assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(1);

			DataSource ds = new SingleConnectionDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword(), true);
			JdbcTemplate jdbc = new JdbcTemplate(ds);

			UUID showId = UUID.randomUUID();
			jdbc.update("INSERT INTO shows (id, name, price_paise, per_user_limit, total_seats) VALUES (?,?,?,?,?)",
					showId, "G2 check", 25000L, 4, 2);
			jdbc.update("INSERT INTO seats (show_id, seat_label, status) VALUES (?,?, 'available')", showId, "A12");
			jdbc.update("INSERT INTO seats (show_id, seat_label, status) VALUES (?,?, 'available')", showId, "A13");

			// Winner confirms A12.
			UUID r1 = UUID.randomUUID();
			jdbc.update("INSERT INTO reservations (id, show_id, user_id, status, amount_paise) VALUES (?,?,?, 'confirmed', ?)",
					r1, showId, "alice", 25000L);
			jdbc.update("INSERT INTO reservation_seats (reservation_id, show_id, seat_label, active) VALUES (?,?,?, TRUE)",
					r1, showId, "A12");
			int updated = jdbc.update(
					"UPDATE seats SET status='confirmed', reservation_id=?, user_id=?, updated_at=now() "
							+ "WHERE show_id=? AND seat_label=? AND status='available'",
					r1, "alice", showId, "A12");
			Assertions.assertThat(updated).isEqualTo(1);

			// Safety net: second active hold of the same seat must be rejected
			// by the partial unique index, even though the guarded UPDATE above
			// would already have declined it with 0 rows affected.
			UUID r2 = UUID.randomUUID();
			jdbc.update("INSERT INTO reservations (id, show_id, user_id, status, amount_paise) VALUES (?,?,?, 'confirmed', ?)",
					r2, showId, "bob", 25000L);
			Assertions.assertThatThrownBy(() -> jdbc.update(
					"INSERT INTO reservation_seats (reservation_id, show_id, seat_label, active) VALUES (?,?,?, TRUE)",
					r2, showId, "A12")).isInstanceOf(DataIntegrityViolationException.class);

			// CHECK: available seats cannot carry an owner.
			Assertions.assertThatThrownBy(() -> jdbc.update(
					"INSERT INTO seats (show_id, seat_label, status, reservation_id, user_id) VALUES (?,?, 'available', ?, ?)",
					showId, "B99", r1, "alice")).isInstanceOf(DataIntegrityViolationException.class);

			// CHECK: quota cannot go negative.
			Assertions.assertThatThrownBy(() -> jdbc.update(
					"INSERT INTO user_show_quota (user_id, show_id, active_seats) VALUES (?,?, -1)",
					"alice", showId)).isInstanceOf(DataIntegrityViolationException.class);

			// Reconciliation invariant: available + held + confirmed == total_seats.
			Map<String, Object> counts = jdbc.queryForMap(
					"SELECT (SELECT COUNT(*) FROM seats WHERE show_id=? AND status='available') AS available, "
							+ "(SELECT COUNT(*) FROM seats WHERE show_id=? AND status='held') AS held, "
							+ "(SELECT COUNT(*) FROM seats WHERE show_id=? AND status='confirmed') AS confirmed, "
							+ "(SELECT total_seats FROM shows WHERE id=?) AS total",
					showId, showId, showId, showId);
			long sum = ((Number) counts.get("available")).longValue()
					+ ((Number) counts.get("held")).longValue()
					+ ((Number) counts.get("confirmed")).longValue();
			Assertions.assertThat(sum).isEqualTo(((Number) counts.get("total")).longValue());
			Assertions.assertThat(counts.get("available")).isEqualTo(1L);
			Assertions.assertThat(counts.get("confirmed")).isEqualTo(1L);
		}
	}
}
