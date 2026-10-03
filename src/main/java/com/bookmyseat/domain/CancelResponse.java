package com.bookmyseat.domain;

import java.util.List;
import java.util.UUID;

/** {@code 200} body for {@code POST /reservations/{id}/cancel}. */
public record CancelResponse(UUID reservationId, UUID showId, String userId, List<String> seats, String status) {
}
