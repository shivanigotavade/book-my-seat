package com.bookmyseat.observability;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * Prometheus exposition at {@code GET /metrics} (G14, public). The registry
 * already carries {@code http_server_requests_seconds}, {@code hikaricp_*}
 * and {@code jvm_*} via auto-configuration; business counters/gauges are
 * registered by {@link ReservationMetrics} and {@link SeatGauges}.
 */
@RestController
public class MetricsController {

	private final PrometheusMeterRegistry registry;

	public MetricsController(PrometheusMeterRegistry registry) {
		this.registry = registry;
	}

	@GetMapping(value = "/metrics", produces = "text/plain; version=0.0.4")
	public ResponseEntity<String> metrics() {
		return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/plain; version=0.0.4"))
				.body(registry.scrape());
	}
}
