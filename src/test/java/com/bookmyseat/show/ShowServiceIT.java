package com.bookmyseat.show;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.bookmyseat.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * G4 acceptance against real PostgreSQL 16: bulk creation is fast and the
 * seat rows reconcile with the show. Skipped without Docker (CI runs it).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ShowServiceIT {

	@Container
	@org.springframework.boot.testcontainers.service.connection.ServiceConnection
	static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	ShowService shows;

	@Autowired
	JdbcTemplate jdbc;

	private static final ObjectMapper JSON = new ObjectMapper();

	private static CreateShowRequest request(String name, List<String> seats, String price, String limit) {
		try {
			return new CreateShowRequest(name, seats, price == null ? null : JSON.readTree(price),
					limit == null ? null : JSON.readTree(limit));
		}
		catch (Exception e) {
			throw new IllegalArgumentException(e);
		}
	}

	@Test
	void createsShowAndBulkSeats() {
		ShowResponse response = shows.createShow(request("Rock Night", List.of("A1", "A2", "A3"), "25000", null));

		assertThat(response.totalSeats()).isEqualTo(3);
		assertThat(response.perUserLimit()).isEqualTo(4);
		assertThat(response.seats()).extracting(ShowResponse.SeatItem::status).containsOnly("available");

		Integer available = jdbc.queryForObject(
				"SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'available'", Integer.class,
				response.id());
		Integer total = jdbc.queryForObject("SELECT total_seats FROM shows WHERE id = ?", Integer.class,
				response.id());
		assertThat(available).isEqualTo(3);
		assertThat(total).isEqualTo(3);
	}

	@Test
	void duplicateLabelsRejectedBeforeWrite() {
		int before = jdbc.queryForObject("SELECT COUNT(*) FROM shows", Integer.class);
		assertThatThrownBy(
				() -> shows.createShow(request("Dup", List.of("A1", "A1"), "100", null)))
				.isInstanceOf(ApiException.class);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shows", Integer.class)).isEqualTo(before);
	}

	@Test
	void fiveThousandSeatShowCompletesQuickly() {
		List<String> seats = new ArrayList<>(5000);
		for (int i = 0; i < 5000; i++) {
			seats.add("S" + i);
		}
		long start = System.nanoTime();
		ShowResponse response = shows.createShow(request("Mega", seats, "999", "6"));
		Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

		assertThat(response.totalSeats()).isEqualTo(5000);
		assertThat(elapsed.getSeconds()).isLessThan(15);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seats WHERE show_id = ?", Integer.class,
				response.id())).isEqualTo(5000);
	}
}
