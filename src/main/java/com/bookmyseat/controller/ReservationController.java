package com.bookmyseat.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.bookmyseat.reservation.CancelResponse;
import com.bookmyseat.reservation.ReservationService;
import com.bookmyseat.reservation.ReserveOutcome;
import com.bookmyseat.reservation.ReserveRequest;
import com.bookmyseat.reservation.ReserveResponse;
import com.bookmyseat.security.AuthPrincipal;

/**
 * {@code POST /shows/{id}/reserve} — authenticated callers (chain-enforced).
 * The owner is always the token principal; body identity fields are ignored.
 */
@RestController
public class ReservationController {

	private final ReservationService reservations;

	public ReservationController(ReservationService reservations) {
		this.reservations = reservations;
	}

	@PostMapping("/shows/{showId}/reserve")
	public ResponseEntity<ReserveResponse> reserve(@PathVariable UUID showId,
			@RequestBody(required = false) ReserveRequest body,
			@RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
			@AuthenticationPrincipal AuthPrincipal principal) {
		ReserveOutcome outcome = reservations.reserve(showId, body == null ? null : body.seats(),
				body == null ? null : body.idempotencyKey(), headerKey, principal);
		var builder = ResponseEntity.status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED);
		if (outcome.replayed()) {
			builder.header("Idempotent-Replayed", "true");
		}
		return builder.body(outcome.response());
	}

	@PostMapping("/reservations/{id}/cancel")
	public ResponseEntity<CancelResponse> cancel(@PathVariable UUID id,
			@AuthenticationPrincipal AuthPrincipal principal) {
		return ResponseEntity.ok(reservations.cancel(id, principal));
	}
}
