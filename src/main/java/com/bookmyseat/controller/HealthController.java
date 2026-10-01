package com.bookmyseat.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bookmyseat.service.HealthService;

/**
 * Liveness vs readiness (G13). Liveness never touches dependencies;
 * readiness fails closed (503) when the database is unreachable so
 * orchestrators stop routing traffic. Both are public.
 */
@RestController
@RequestMapping("/health")
public class HealthController {

	private final HealthService health;

	public HealthController(HealthService health) {
		this.health = health;
	}

	@GetMapping("/live")
	public Map<String, String> live() {
		return Map.of("status", "UP");
	}

	@GetMapping("/ready")
	public ResponseEntity<Map<String, String>> ready() {
		if (health.isDbUp()) {
			return ResponseEntity.ok(Map.of("status", "UP", "db", "UP"));
		}
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(Map.of("status", "DOWN", "db", "DOWN"));
	}
}
