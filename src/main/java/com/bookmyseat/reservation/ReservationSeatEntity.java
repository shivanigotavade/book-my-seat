package com.bookmyseat.reservation;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** Safety-net rows: the partial unique index behind them lives in Flyway. */
@Entity
@Table(name = "reservation_seats")
@IdClass(ReservationSeatEntity.PK.class)
public class ReservationSeatEntity {

	@Id
	@Column(name = "reservation_id")
	private UUID reservationId;

	@Id
	@Column(name = "show_id")
	private UUID showId;

	@Id
	@Column(name = "seat_label")
	private String seatLabel;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	protected ReservationSeatEntity() {
	}

	public ReservationSeatEntity(UUID reservationId, UUID showId, String seatLabel) {
		this.reservationId = reservationId;
		this.showId = showId;
		this.seatLabel = seatLabel;
	}

	public String getSeatLabel() {
		return seatLabel;
	}

	public static class PK implements Serializable {

		private UUID reservationId;
		private UUID showId;
		private String seatLabel;

		public PK() {
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof PK other)) {
				return false;
			}
			return Objects.equals(reservationId, other.reservationId) && Objects.equals(showId, other.showId)
					&& Objects.equals(seatLabel, other.seatLabel);
		}

		@Override
		public int hashCode() {
			return Objects.hash(reservationId, showId, seatLabel);
		}
	}
}
