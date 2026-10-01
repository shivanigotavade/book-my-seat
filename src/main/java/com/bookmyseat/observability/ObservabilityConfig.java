package com.bookmyseat.observability;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.bookmyseat.repository.SeatRepository;

import io.micrometer.core.instrument.MeterRegistry;

/** Registers business observability beans (G14). */
@Configuration
public class ObservabilityConfig {

	@Bean
	ReservationMetrics reservationMetrics(MeterRegistry registry) {
		return new ReservationMetrics(registry);
	}

	@Bean
	SeatGauges seatGauges(SeatRepository seats, MeterRegistry registry) {
		return new SeatGauges(seats, registry);
	}
}
