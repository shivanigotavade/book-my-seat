package com.bookmyseat.show;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Maps {@code seats}. The {@code version} column is a plain number here —
 * deliberately <b>not</b> {@code @Version}: concurrency is governed by
 * pessimistic row locks plus guarded updates, not optimistic versioning.
 */
@Entity
@Table(name = "seats")
public class SeatEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@Column(name = "show_id", nullable = false)
	private UUID showId;

	@Column(name = "seat_label", nullable = false)
	private String seatLabel;

	@Column(name = "status", nullable = false)
	private String status;

	@Column(name = "reservation_id")
	private UUID reservationId;

	@Column(name = "user_id")
	private String userId;

	@Column(name = "version", nullable = false)
	private int version;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected SeatEntity() {
	}

	public Long getId() {
		return id;
	}

	public UUID getShowId() {
		return showId;
	}

	public String getSeatLabel() {
		return seatLabel;
	}

	public String getStatus() {
		return status;
	}

	public UUID getReservationId() {
		return reservationId;
	}

	public String getUserId() {
		return userId;
	}
}
