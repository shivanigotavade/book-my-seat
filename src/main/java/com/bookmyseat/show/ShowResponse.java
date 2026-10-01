package com.bookmyseat.show;

import java.util.List;
import java.util.UUID;

/**
 * {@code 201} body for {@code POST /shows}. Every seat is
 * {@code available} at creation time.
 */
public record ShowResponse(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats,
		List<SeatItem> seats) {

	public record SeatItem(String seat, String status) {
	}
}
