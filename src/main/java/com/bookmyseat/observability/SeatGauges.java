package com.bookmyseat.observability;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.jdbc.core.JdbcTemplate;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Seat gauges computed from the database (G14), so they cannot drift from
 * reality after restarts. Aggregate (no {@code show_id} label) to keep
 * cardinality flat; refreshed at most once per second.
 */
public class SeatGauges {

	private static final long TTL_MILLIS = 1000;

	private final JdbcTemplate jdbc;
	private final AtomicReference<Map<String, Long>> cached = new AtomicReference<>(Map.of());
	private final AtomicLong refreshedAt = new AtomicLong(0);

	public SeatGauges(JdbcTemplate jdbc, MeterRegistry registry) {
		this.jdbc = jdbc;
		Gauge.builder("bookmyseat_seats_available", this, g -> g.value("available"))
				.description("Seats currently available").register(registry);
		Gauge.builder("bookmyseat_seats_held", this, g -> g.value("held")).description("Seats currently held")
				.register(registry);
		Gauge.builder("bookmyseat_seats_confirmed", this, g -> g.value("confirmed"))
				.description("Seats currently confirmed").register(registry);
	}

	double value(String status) {
		long now = System.currentTimeMillis();
		Map<String, Long> snapshot = cached.get();
		if (snapshot.isEmpty() || now - refreshedAt.get() >= TTL_MILLIS) {
			synchronized (this) {
				if (cached.get().isEmpty() || now - refreshedAt.get() >= TTL_MILLIS) {
					cached.set(load());
					refreshedAt.set(now);
				}
				snapshot = cached.get();
			}
		}
		return snapshot.getOrDefault(status, 0L);
	}

	private Map<String, Long> load() {
		Map<String, Long> counts = new ConcurrentHashMap<>(Map.of("available", 0L, "held", 0L, "confirmed", 0L));
		jdbc.queryForList("SELECT status, COUNT(*) AS n FROM seats GROUP BY status")
				.forEach(row -> counts.put((String) row.get("status"), ((Number) row.get("n")).longValue()));
		return Map.copyOf(counts);
	}
}
