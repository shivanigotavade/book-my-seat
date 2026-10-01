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

import com.bookmyseat.security.AuthPrincipal;
import com.bookmyseat.service.HealthService;
import com.bookmyseat.service.ReservationService;
import com.bookmyseat.service.ShowService;
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
	ReservationService reserves;

	@Autowired
	HealthService health;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	io.micrometer.core.instrument.MeterRegistry meterRegistry;

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

	@Test
	void stateCountsShiftAndReconcile() throws Exception {
		ShowResponse created = shows.createShow(request("State", List.of("A1", "A2", "A3"), "100", null));

		ShowDetailResponse fresh = shows.getShow(created.id(), false);
		assertThat(fresh.counts().available()).isEqualTo(3);
		assertThat(fresh.counts().confirmed()).isEqualTo(0);
		assertThat(fresh.seats()).extracting(ShowResponse.SeatItem::status).containsOnly("available");

		reserves.reserve(created.id(), List.of("A1"),
				com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.textNode("k-1"), null,
				new AuthPrincipal("alice", "USER"));

		ShowDetailResponse after = shows.getShow(created.id(), false);
		assertThat(after.counts().available()).isEqualTo(2);
		assertThat(after.counts().confirmed()).isEqualTo(1);
		assertThat(after.counts().available() + after.counts().held() + after.counts().confirmed())
				.isEqualTo(after.totalSeats());

		ShowDetailResponse summary = shows.getShow(created.id(), true);
		assertThat(summary.counts().available()).isEqualTo(2);
		assertThat(summary.seats()).isNull();

		assertThatThrownBy(() -> shows.getShow(java.util.UUID.randomUUID(), false))
				.isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.getCode()).isEqualTo("NOT_FOUND"));
	}

	@Test
	void gaugesAndCountersReconcileWithApi() throws Exception {
		ShowResponse created = shows.createShow(request("Gauges", List.of("G1", "G2"), "100", null));
		double confirmedBefore = gauge("bookmyseat_seats_confirmed");
		double availableBefore = gauge("bookmyseat_seats_available");

		reserves.reserve(created.id(), List.of("G1"),
				com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.textNode("gk-1"), null,
				new AuthPrincipal("alice", "USER"));

		// Gauges cache for 1s; wait past the TTL so the scrape re-queries.
		Thread.sleep(1100);
		assertThat(gauge("bookmyseat_seats_confirmed")).isEqualTo(confirmedBefore + 1);
		assertThat(gauge("bookmyseat_seats_available")).isEqualTo(availableBefore - 1);
		assertThat(counter("bookmyseat_reservations_confirmed_total")).isGreaterThanOrEqualTo(1);
	}

	private double gauge(String name) {
		var gauge = meterRegistry.find(name).gauge();
		assertThat(gauge).as("gauge %s registered", name).isNotNull();
		return gauge.value();
	}

	private double counter(String name) {
		var counter = meterRegistry.find(name).counter();
		assertThat(counter).as("counter %s registered", name).isNotNull();
		return counter.count();
	}

	@Test
	void readinessSeesLiveDatabase() {
		assertThat(health.isDbUp()).isTrue();
	}
}
