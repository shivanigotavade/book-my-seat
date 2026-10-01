package com.bookmyseat.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.bookmyseat.entity.ReservationEntity;

public interface ReservationRepository extends JpaRepository<ReservationEntity, UUID> {

	/** Guarded transition: exactly one concurrent cancel can win. */
	@Modifying
	@Query(value = "UPDATE reservations SET status='cancelled', cancelled_at=now()"
			+ " WHERE id=:id AND user_id=:userId AND status='confirmed'", nativeQuery = true)
	int cancelReservation(UUID id, String userId);
}
