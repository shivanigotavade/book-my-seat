package com.bookmyseat.domain;

import java.util.List;
import java.util.UUID;

/**
 * {@code 201} body for {@code POST /shows/{id}/reserve}. Snake-case wire
 * names come from the global {@code SNAKE_CASE} strategy.
 */
public record ReserveResponse(UUID reservationId, UUID showId, String userId, List<String> seats, long amountPaise,
		String status) {
}
