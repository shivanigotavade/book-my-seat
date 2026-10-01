package com.bookmyseat.reservation;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_show_quota")
@IdClass(UserShowQuotaEntity.PK.class)
public class UserShowQuotaEntity {

	@Id
	@Column(name = "user_id")
	private String userId;

	@Id
	@Column(name = "show_id")
	private UUID showId;

	@Column(name = "active_seats", nullable = false)
	private int activeSeats;

	protected UserShowQuotaEntity() {
	}

	public static class PK implements Serializable {

		private String userId;
		private UUID showId;

		public PK() {
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof PK other)) {
				return false;
			}
			return Objects.equals(userId, other.userId) && Objects.equals(showId, other.showId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(userId, showId);
		}
	}
}
