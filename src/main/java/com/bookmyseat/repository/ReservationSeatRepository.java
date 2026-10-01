package com.bookmyseat.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.bookmyseat.entity.ReservationSeatEntity;

public interface ReservationSeatRepository extends JpaRepository<ReservationSeatEntity, ReservationSeatEntity.PK> {

	@Query("SELECT r.seatLabel FROM ReservationSeatEntity r WHERE r.reservationId = :reservationId ORDER BY r.seatLabel")
	List<String> findLabels(UUID reservationId);

	@Modifying
	@Query(value = "UPDATE reservation_seats SET active=FALSE WHERE reservation_id=:reservationId AND active",
			nativeQuery = true)
	int deactivate(UUID reservationId);
}
