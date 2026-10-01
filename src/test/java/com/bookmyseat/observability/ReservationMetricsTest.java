package com.bookmyseat.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ReservationMetricsTest {

	private final MeterRegistry registry = new SimpleMeterRegistry();
	private final ReservationMetrics metrics = new ReservationMetrics(registry);

	private double count(String name, String... tags) {
		var search = registry.find(name);
		for (int i = 0; i < tags.length; i += 2) {
			search = search.tag(tags[i], tags[i + 1]);
		}
		var counter = search.counter();
		return counter == null ? 0 : counter.count();
	}

	@Test
	void confirmedAndCancelledCountAfterCommit() {
		metrics.confirmed();
		metrics.confirmed();
		metrics.cancelled();
		metrics.released(2);
		assertThat(count("bookmyseat_reservations_confirmed_total")).isEqualTo(2);
		assertThat(count("bookmyseat_reservations_cancelled_total")).isEqualTo(1);
		assertThat(count("bookmyseat_seats_released_total")).isEqualTo(2);
	}

	@Test
	void declineCodesMapToReasons() {
		assertThat(metrics.declined("SEAT_TAKEN")).isTrue();
		assertThat(metrics.declined("PER_USER_LIMIT")).isTrue();
		assertThat(metrics.declined("IDEMPOTENCY_KEY_REUSED")).isTrue();
		assertThat(metrics.declined("INVALID_SEAT")).isTrue();
		assertThat(count("bookmyseat_reservations_declined_total", "reason", "seat-taken")).isEqualTo(1);
		assertThat(count("bookmyseat_reservations_declined_total", "reason", "per-user-limit")).isEqualTo(1);
		assertThat(count("bookmyseat_reservations_declined_total", "reason", "idempotency-key-reused"))
				.isEqualTo(1);
		assertThat(count("bookmyseat_reservations_declined_total", "reason", "invalid-seat")).isEqualTo(1);
	}

	@Test
	void unlistedCodesAreUncounted() {
		assertThat(metrics.declined("NOT_FOUND")).isFalse();
		assertThat(metrics.declined("VALIDATION_ERROR")).isFalse();
		assertThat(registry.find("bookmyseat_reservations_declined_total").counter()).isNull();
	}

	@Test
	void replaysAndRetries() {
		metrics.replayed();
		metrics.retried("deadlock");
		assertThat(count("bookmyseat_reservations_declined_total", "reason", "idempotent-replay")).isEqualTo(1);
		assertThat(count("bookmyseat_tx_retries_total", "cause", "deadlock")).isEqualTo(1);
	}
}
