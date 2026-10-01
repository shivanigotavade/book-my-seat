package com.bookmyseat.observability;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import io.micrometer.core.instrument.MeterRegistry;

/** Registers business observability beans (G14). */
@Configuration
public class ObservabilityConfig {

	@Bean
	ReservationMetrics reservationMetrics(MeterRegistry registry) {
		return new ReservationMetrics(registry);
	}

	@Bean
	SeatGauges seatGauges(JdbcTemplate jdbc, MeterRegistry registry) {
		return new SeatGauges(jdbc, registry);
	}
}
