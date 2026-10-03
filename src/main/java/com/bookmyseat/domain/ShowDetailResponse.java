package com.bookmyseat.domain;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code 200} body for {@code GET /shows/{id}}. Counts and seat list always
 * come from one snapshot (single {@code REPEATABLE READ} read-only
 * transaction), so {@code available + held + confirmed == total_seats} holds
 * even mid-burst. With {@code ?summary=true} the seat list is omitted.
 */
public record ShowDetailResponse(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats,
		Counts counts, @JsonInclude(JsonInclude.Include.NON_NULL) List<ShowResponse.SeatItem> seats) {

	public record Counts(long available, long held, long confirmed) {
	}
}
