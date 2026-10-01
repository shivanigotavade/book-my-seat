package com.bookmyseat.entity;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** Exactly-once keys, scoped per user. Written in the reserve transaction. */
@Entity
@Table(name = "idempotency_keys")
@IdClass(IdempotencyKeyEntity.PK.class)
public class IdempotencyKeyEntity {

	@Id
	@Column(name = "user_id")
	private String userId;

	@Id
	@Column(name = "idempotency_key")
	private String idempotencyKey;

	@Column(name = "request_hash", nullable = false)
	private String requestHash;

	@Column(name = "reservation_id")
	private UUID reservationId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected IdempotencyKeyEntity() {
	}

	public IdempotencyKeyEntity(String userId, String idempotencyKey, String requestHash) {
		this.userId = userId;
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
	}

	public String getRequestHash() {
		return requestHash;
	}

	public UUID getReservationId() {
		return reservationId;
	}

	public void setReservationId(UUID reservationId) {
		this.reservationId = reservationId;
	}

	public static class PK implements Serializable {

		private String userId;
		private String idempotencyKey;

		public PK() {
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof PK other)) {
				return false;
			}
			return Objects.equals(userId, other.userId) && Objects.equals(idempotencyKey, other.idempotencyKey);
		}

		@Override
		public int hashCode() {
			return Objects.hash(userId, idempotencyKey);
		}
	}
}
