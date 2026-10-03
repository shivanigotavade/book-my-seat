package com.bookmyseat.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.bookmyseat.domain.CancelResponse;
import com.bookmyseat.domain.CreateShowRequest;
import com.bookmyseat.domain.ReserveOutcome;
import com.bookmyseat.domain.ReserveResponse;
import com.bookmyseat.security.AuthPrincipal;
import com.bookmyseat.service.ReservationService;
import com.bookmyseat.service.ShowService;
import com.bookmyseat.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * G5 acceptance against real PostgreSQL 16: one winner per hot seat, clean
 * 409s, no 5xx-shaped surprises (every decline is {@link ApiException}).
 * Skipped without Docker (CI runs it).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ReserveServiceIT {

	@Container
	@org.springframework.boot.testcontainers.service.connection.ServiceConnection
	static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

	@Autowired
	ShowService shows;

	@Autowired
	ReservationService reserves;

	@Autowired
	JdbcTemplate jdbc;

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final AuthPrincipal ALICE = new AuthPrincipal("alice", "USER");

	private UUID createShow(List<String> seats, String price, String limit) {
		try {
			var request = new CreateShowRequest("Hot Sale", seats, JSON.readTree(price),
					limit == null ? null : JSON.readTree(limit));
			return shows.createShow(request).id();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static com.fasterxml.jackson.databind.JsonNode key(String k) {
		try {
			return JSON.readTree("\"" + k + "\"");
		}
		catch (Exception e) {
			throw new IllegalArgumentException(e);
		}
	}

	@Test
	void hotSeatRaceYieldsExactlyOneWinner() throws Exception {
		UUID showId = createShow(List.of("A12", "A13"), "25000", null);
		int racers = 100;
		var start = new CountDownLatch(1);
		var done = new CountDownLatch(racers);
		var winners = new AtomicInteger();
		var codes = new ConcurrentHashMap<String, AtomicInteger>();
		var unexpected = new ConcurrentHashMap<String, String>();

		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < racers; i++) {
				final int n = i;
				pool.submit(() -> {
					try {
						start.await();
						reserves.reserve(showId, List.of("A12"), key("k-" + n),
								null, new AuthPrincipal("u-" + n, "USER"));
						winners.incrementAndGet();
					}
					catch (ApiException e) {
						codes.computeIfAbsent(e.getCode(), c -> new AtomicInteger()).incrementAndGet();
					}
					catch (Exception e) {
						unexpected.put("u-" + n, e.toString());
					}
					finally {
						done.countDown();
					}
					return null;
				});
			}
			start.countDown();
			assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(unexpected).isEmpty();
		assertThat(winners.get()).isEqualTo(1);
		assertThat(codes.getOrDefault("SEAT_TAKEN", new AtomicInteger()).get()).isEqualTo(racers - 1);
		assertThat(codes.keySet()).containsExactly("SEAT_TAKEN");

		Integer active = jdbc.queryForObject(
				"SELECT COUNT(*) FROM reservation_seats WHERE show_id = ? AND seat_label = 'A12' AND active",
				Integer.class, showId);
		assertThat(active).isEqualTo(1);
	}

	@Test
	void sequentialSecondBookingDeclined() {
		UUID showId = createShow(List.of("A12", "A13"), "25000", null);
		ReserveOutcome first = reserves.reserve(showId, List.of("A13"), key("k-1"), null, ALICE);
		assertThat(first.replayed()).isFalse();
		assertThat(first.response().status()).isEqualTo("confirmed");
		assertThat(first.response().amountPaise()).isEqualTo(25000L);

		assertThatThrownBy(
				() -> reserves.reserve(showId, List.of("A13"), key("k-2"), null,
						new AuthPrincipal("bob", "USER")))
				.isInstanceOf(ApiException.class).hasMessageContaining("no longer available");
	}

	@Test
	void unknownSeatAndShowAre404() {
		UUID showId = createShow(List.of("A12"), "25000", null);
		assertThatThrownBy(() -> reserves.reserve(showId, List.of("ZZ9"), key("k-1"), null, ALICE))
				.isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.getCode()).isEqualTo("INVALID_SEAT"));
		assertThatThrownBy(() -> reserves.reserve(UUID.randomUUID(), List.of("A12"), key("k-1"), null, ALICE))
				.isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.getCode()).isEqualTo("NOT_FOUND"));
	}

	@Test
	void overLimitRequestFailsFast() {
		UUID showId = createShow(List.of("A1", "A2", "A3"), "100", "2");
		assertThatThrownBy(() -> reserves.reserve(showId, List.of("A1", "A2", "A3"), key("k-1"), null, ALICE))
				.isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.getCode()).isEqualTo("PER_USER_LIMIT"));
	}

	@Test
	void perUserLimitRaceCapsAtFour() throws Exception {
		List<String> seats = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			seats.add("S" + i);
		}
		UUID showId = createShow(seats, "100", null);
		int racers = 10;
		var start = new CountDownLatch(1);
		var done = new CountDownLatch(racers);
		var wins = new AtomicInteger();
		var codes = new ConcurrentHashMap<String, AtomicInteger>();

		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < racers; i++) {
				final int n = i;
				pool.submit(() -> {
					try {
						start.await();
						reserves.reserve(showId, List.of("S" + n), key("q-" + n), null, ALICE);
						wins.incrementAndGet();
					}
					catch (ApiException e) {
						codes.computeIfAbsent(e.getCode(), c -> new AtomicInteger()).incrementAndGet();
					}
					catch (Exception e) {
						codes.computeIfAbsent("UNEXPECTED:" + e, c -> new AtomicInteger()).incrementAndGet();
					}
					finally {
						done.countDown();
					}
					return null;
				});
			}
			start.countDown();
			assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(wins.get()).isEqualTo(4);
		assertThat(codes.keySet()).containsExactly("PER_USER_LIMIT");
		assertThat(jdbc.queryForObject(
				"SELECT active_seats FROM user_show_quota WHERE user_id = 'alice' AND show_id = ?",
				Integer.class, showId)).isEqualTo(4);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'confirmed' AND user_id = 'alice'",
				Integer.class, showId)).isEqualTo(4);
	}

	@Test
	void failedMultiSeatRequestLeavesQuotaUntouched() {
		UUID showId = createShow(List.of("A1", "A2", "A3", "A4", "A5"), "100", null);
		reserves.reserve(showId, List.of("A1"), key("k-1"), null, ALICE);
		reserves.reserve(showId, List.of("A2"), key("k-2"), null, ALICE);
		reserves.reserve(showId, List.of("A3"), key("k-3"), null, ALICE);

		assertThatThrownBy(() -> reserves.reserve(showId, List.of("A4", "A5"), key("k-4"), null, ALICE))
				.isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.getCode()).isEqualTo("PER_USER_LIMIT"));
		assertThat(jdbc.queryForObject(
				"SELECT active_seats FROM user_show_quota WHERE user_id = 'alice' AND show_id = ?",
				Integer.class, showId)).isEqualTo(3);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM seats WHERE show_id = ? AND seat_label IN ('A4','A5') AND status = 'available'",
				Integer.class, showId)).isEqualTo(2);

		reserves.reserve(showId, List.of("A4"), key("k-5"), null, ALICE);
		assertThatThrownBy(() -> reserves.reserve(showId, List.of("A5"), key("k-6"), null, ALICE))
				.isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.getCode()).isEqualTo("PER_USER_LIMIT"));
	}

	@Test
	void ownerComesFromTokenAndAmountMultiplies() {
		UUID showId = createShow(List.of("A1", "A2"), "25000", null);
		ReserveOutcome outcome = reserves.reserve(showId, List.of("A2", "A1"), key("k-1"), null, ALICE);
		assertThat(outcome.replayed()).isFalse();
		ReserveResponse response = outcome.response();
		assertThat(response.userId()).isEqualTo("alice");
		assertThat(response.seats()).containsExactly("A1", "A2");
		assertThat(response.amountPaise()).isEqualTo(50000L);

		Map<String, Object> row = jdbc.queryForMap("SELECT user_id FROM seats WHERE show_id = ? AND seat_label = 'A1'",
				showId);
		assertThat(row.get("user_id")).isEqualTo("alice");
	}

	@Test
	void sameKeyRaceYieldsOneReservationAndReplays() throws Exception {
		UUID showId = createShow(List.of("A12", "A13"), "25000", null);
		int racers = 50;
		var start = new CountDownLatch(1);
		var done = new CountDownLatch(racers);
		var firsts = new ConcurrentHashMap<UUID, Boolean>();
		var replays = new ConcurrentHashMap<UUID, Boolean>();
		var codes = new ConcurrentHashMap<String, AtomicInteger>();

		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < racers; i++) {
				pool.submit(() -> {
					try {
						start.await();
						ReserveOutcome outcome = reserves.reserve(showId, List.of("A12"), key("same"), null,
								ALICE);
						(outcome.replayed() ? replays : firsts).put(outcome.response().reservationId(), true);
					}
					catch (ApiException e) {
						codes.computeIfAbsent(e.getCode(), c -> new AtomicInteger()).incrementAndGet();
					}
					catch (Exception e) {
						codes.computeIfAbsent("UNEXPECTED:" + e, c -> new AtomicInteger()).incrementAndGet();
					}
					finally {
						done.countDown();
					}
					return null;
				});
			}
			start.countDown();
			assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(codes).isEmpty();
		assertThat(firsts.keySet()).hasSize(1);
		UUID only = firsts.keySet().iterator().next();
		assertThat(replays.keySet()).containsExactly(only);
		assertThat(firsts.size() + replays.size()).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservations WHERE show_id = ?", Integer.class,
				showId)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"SELECT active_seats FROM user_show_quota WHERE user_id = 'alice' AND show_id = ?",
				Integer.class, showId)).isEqualTo(1);
	}

	@Test
	void sameKeyDifferentBodyIs409() {
		UUID showId = createShow(List.of("A12", "A13"), "25000", null);
		reserves.reserve(showId, List.of("A12"), key("k"), null, ALICE);
		assertThatThrownBy(() -> reserves.reserve(showId, List.of("A13"), key("k"), null, ALICE))
				.isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
	}

	@Test
	void declinedAttemptDoesNotConsumeKey() {
		UUID showId = createShow(List.of("A12", "A13"), "25000", null);
		reserves.reserve(showId, List.of("A12"), key("alice-k"), null, ALICE);
		// Bob's attempt on the taken seat declines and frees his key.
		assertThatThrownBy(
				() -> reserves.reserve(showId, List.of("A12"), key("bob-k"), null,
						new AuthPrincipal("bob", "USER")))
				.isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.getCode()).isEqualTo("SEAT_TAKEN"));
		// Same key works for a free seat.
		ReserveOutcome retry = reserves.reserve(showId, List.of("A13"), key("bob-k"), null,
				new AuthPrincipal("bob", "USER"));
		assertThat(retry.replayed()).isFalse();
		assertThat(retry.response().seats()).containsExactly("A13");
	}

	@Test
	void partialAvailabilityDeclinesWholeRequest() {
		UUID showId = createShow(List.of("A12", "A13", "A14"), "100", null);
		reserves.reserve(showId, List.of("A13"), key("k-1"), null, ALICE);

		assertThatThrownBy(
				() -> reserves.reserve(showId, List.of("A12", "A13"), key("k-2"), null,
						new AuthPrincipal("bob", "USER")))
				.isInstanceOfSatisfying(ApiException.class, e -> {
					assertThat(e.getCode()).isEqualTo("SEAT_TAKEN");
				});
		// Nothing held: the free seat stayed available and no quota was consumed.
		assertThat(jdbc.queryForObject(
				"SELECT status FROM seats WHERE show_id = ? AND seat_label = 'A12'", String.class,
				showId)).isEqualTo("available");
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM user_show_quota WHERE user_id = 'bob' AND show_id = ?", Integer.class,
				showId)).isEqualTo(0);
		assertThat(jdbc.queryForObject(
				"SELECT active_seats FROM user_show_quota WHERE user_id = 'alice' AND show_id = ?",
				Integer.class, showId)).isEqualTo(1);
	}

	@Test
	void overlappingMultiSeatRequestsNeverDeadlockNorPartiallyGrant() throws Exception {
		List<String> seats = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			seats.add("B" + i);
		}
		UUID showId = createShow(seats, "100", "1000");
		int requests = 1000;
		var start = new CountDownLatch(1);
		var done = new CountDownLatch(requests);
		var wins = new AtomicInteger();
		var codes = new ConcurrentHashMap<String, AtomicInteger>();

		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < requests; i++) {
				final int n = i;
				pool.submit(() -> {
					try {
						start.await();
						int pair = n % 4;
						// Odd tasks list the pair reversed; normalisation must
						// still lock both orders identically.
						List<String> want = n % 2 == 0 ? List.of("B" + (2 * pair), "B" + (2 * pair + 1))
								: List.of("B" + (2 * pair + 1), "B" + (2 * pair));
						reserves.reserve(showId, want, key("m-" + n), null,
								new AuthPrincipal("u-" + (n % 50), "USER"));
						wins.incrementAndGet();
					}
					catch (ApiException e) {
						codes.computeIfAbsent(e.getCode(), c -> new AtomicInteger()).incrementAndGet();
					}
					catch (Exception e) {
						codes.computeIfAbsent("UNEXPECTED:" + e, c -> new AtomicInteger()).incrementAndGet();
					}
					finally {
						done.countDown();
					}
					return null;
				});
			}
			start.countDown();
			assertThat(done.await(180, TimeUnit.SECONDS)).isTrue();
		}

		// Only clean domain declines; no deadlock/5xx-shaped surprise.
		assertThat(codes.keySet()).containsExactly("SEAT_TAKEN");
		// Exactly one winner per pair: 4 pairs, 2 seats each.
		assertThat(wins.get()).isEqualTo(4);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'confirmed'", Integer.class,
				showId)).isEqualTo(8);
		// No partial grants: both seats of a pair share one reservation.
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(DISTINCT reservation_id) FROM seats WHERE show_id = ?", Integer.class,
				showId)).isEqualTo(4);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM reservation_seats WHERE show_id = ? AND active", Integer.class,
				showId)).isEqualTo(8);
	}

	@Test
	void cancelThenRebookByAnotherUser() {
		UUID showId = createShow(List.of("A12", "A13"), "25000", null);
		ReserveOutcome first = reserves.reserve(showId, List.of("A12"), key("k-1"), null, ALICE);

		CancelResponse cancelled = reserves.cancel(first.response().reservationId(), ALICE);
		assertThat(cancelled.status()).isEqualTo("cancelled");
		assertThat(cancelled.seats()).containsExactly("A12");
		assertThat(jdbc.queryForObject(
				"SELECT status FROM seats WHERE show_id = ? AND seat_label = 'A12'", String.class,
				showId)).isEqualTo("available");
		assertThat(jdbc.queryForObject(
				"SELECT active_seats FROM user_show_quota WHERE user_id = 'alice' AND show_id = ?",
				Integer.class, showId)).isEqualTo(0);

		AuthPrincipal bob = new AuthPrincipal("bob", "USER");
		ReserveOutcome rebook = reserves.reserve(showId, List.of("A12"), key("k-1"), null, bob);
		assertThat(rebook.replayed()).isFalse();
		assertThat(rebook.response().userId()).isEqualTo("bob");
	}

	@Test
	void lateDuplicateCancelNeverFreesNewOwnerSeat() {
		UUID showId = createShow(List.of("A12"), "25000", null);
		ReserveOutcome first = reserves.reserve(showId, List.of("A12"), key("k-1"), null, ALICE);
		reserves.cancel(first.response().reservationId(), ALICE);

		AuthPrincipal bob = new AuthPrincipal("bob", "USER");
		reserves.reserve(showId, List.of("A12"), key("k-2"), null, bob);

		// Alice's stale cancel is idempotent and touches nothing of Bob's.
		CancelResponse repeat = reserves.cancel(first.response().reservationId(), ALICE);
		assertThat(repeat.status()).isEqualTo("cancelled");
		assertThat(jdbc.queryForObject(
				"SELECT user_id FROM seats WHERE show_id = ? AND seat_label = 'A12'", String.class,
				showId)).isEqualTo("bob");
		assertThat(jdbc.queryForObject(
				"SELECT status FROM seats WHERE show_id = ? AND seat_label = 'A12'", String.class,
				showId)).isEqualTo("confirmed");
		assertThat(jdbc.queryForObject(
				"SELECT active_seats FROM user_show_quota WHERE user_id = 'bob' AND show_id = ?",
				Integer.class, showId)).isEqualTo(1);
	}

	@Test
	void nonOwnerCancelIs403() {
		UUID showId = createShow(List.of("A12"), "25000", null);
		ReserveOutcome first = reserves.reserve(showId, List.of("A12"), key("k-1"), null, ALICE);
		assertThatThrownBy(() -> reserves.cancel(first.response().reservationId(),
				new AuthPrincipal("bob", "USER"))).isInstanceOfSatisfying(ApiException.class, e -> {
					assertThat(e.getCode()).isEqualTo("FORBIDDEN");
					assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
				});
		assertThatThrownBy(() -> reserves.cancel(UUID.randomUUID(), ALICE))
				.isInstanceOfSatisfying(ApiException.class,
						e -> assertThat(e.getCode()).isEqualTo("NOT_FOUND"));
	}

	@Test
	void concurrentDoubleCancelIsIdempotent() throws Exception {
		UUID showId = createShow(List.of("A12"), "25000", null);
		ReserveOutcome first = reserves.reserve(showId, List.of("A12"), key("k-1"), null, ALICE);
		UUID reservationId = first.response().reservationId();

		int racers = 10;
		var start = new CountDownLatch(1);
		var done = new CountDownLatch(racers);
		var oks = new AtomicInteger();
		var codes = new ConcurrentHashMap<String, AtomicInteger>();

		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < racers; i++) {
				pool.submit(() -> {
					try {
						start.await();
						CancelResponse response = reserves.cancel(reservationId, ALICE);
						assertThat(response.status()).isEqualTo("cancelled");
						oks.incrementAndGet();
					}
					catch (ApiException e) {
						codes.computeIfAbsent(e.getCode(), c -> new AtomicInteger()).incrementAndGet();
					}
					catch (Exception e) {
						codes.computeIfAbsent("UNEXPECTED:" + e, c -> new AtomicInteger()).incrementAndGet();
					}
					finally {
						done.countDown();
					}
					return null;
				});
			}
			start.countDown();
			assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(codes).isEmpty();
		assertThat(oks.get()).isEqualTo(racers);
		assertThat(jdbc.queryForObject(
				"SELECT status FROM seats WHERE show_id = ? AND seat_label = 'A12'", String.class,
				showId)).isEqualTo("available");
		assertThat(jdbc.queryForObject(
				"SELECT active_seats FROM user_show_quota WHERE user_id = 'alice' AND show_id = ?",
				Integer.class, showId)).isEqualTo(0);
	}
}
