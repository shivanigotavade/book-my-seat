package com.bookmyseat.reservation;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "reservations")
public class ReservationEntity {

	@Id
	@Column(name = "id")
	private UUID id;

	@Column(name = "show_id", nullable = false)
	private UUID showId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "status", nullable = false)
	private String status;

	@Column(name = "amount_paise", nullable = false)
	private long amountPaise;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "cancelled_at")
	private Instant cancelledAt;

	protected ReservationEntity() {
	}

	public ReservationEntity(UUID id, UUID showId, String userId, long amountPaise) {
		this.id = id;
		this.showId = showId;
		this.userId = userId;
		this.status = "confirmed";
		this.amountPaise = amountPaise;
	}

	public UUID getId() {
		return id;
	}

	public UUID getShowId() {
		return showId;
	}

	public String getUserId() {
		return userId;
	}

	public String getStatus() {
		return status;
	}

	public long getAmountPaise() {
		return amountPaise;
	}
}
