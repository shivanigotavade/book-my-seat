package com.bookmyseat.observability;

import java.util.Map;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Business counters (G14). Confirmed/cancelled increment only after commit;
 * declines increment per reason; replays increment
 * {@code idempotent-replay} only. Reconciliation rule:
 * {@code total_seats − seats_available == confirmed − released} per show.
 */
public class ReservationMetrics {

	/** Brief §7 decline codes counted here; anything else is uncounted. */
	static final Map<String, String> DECLINE_REASONS = Map.of("SEAT_TAKEN", "seat-taken",
			"PER_USER_LIMIT", "per-user-limit", "IDEMPOTENCY_KEY_REUSED", "idempotency-key-reused",
			"INVALID_SEAT", "invalid-seat");

	private final Counter confirmed;
	private final Counter cancelled;
	private final Counter released;
	private final MeterRegistry registry;

	public ReservationMetrics(MeterRegistry registry) {
		this.registry = registry;
		this.confirmed = Counter.builder("bookmyseat_reservations_confirmed_total")
				.description("Successful new reservations").register(registry);
		this.cancelled = Counter.builder("bookmyseat_reservations_cancelled_total").description("Cancellations")
				.register(registry);
		this.released = Counter.builder("bookmyseat_seats_released_total")
				.description("Seats returned to the pool").register(registry);
	}

	public void confirmed() {
		confirmed.increment();
	}

	public void replayed() {
		countDeclined("idempotent-replay");
	}

	/** Returns false when the code is not a counted decline reason. */
	public boolean declined(String code) {
		String reason = DECLINE_REASONS.get(code);
		if (reason == null) {
			return false;
		}
		countDeclined(reason);
		return true;
	}

	private void countDeclined(String reason) {
		Counter.builder("bookmyseat_reservations_declined_total").tag("reason", reason)
				.description("Declines and replays by reason").register(registry).increment();
	}

	public void cancelled() {
		cancelled.increment();
	}

	public void released(int seats) {
		released.increment(seats);
	}

	public void retried(String cause) {
		Counter.builder("bookmyseat_tx_retries_total").tag("cause", cause)
				.description("Deadlock/serialization retries").register(registry).increment();
	}
}
