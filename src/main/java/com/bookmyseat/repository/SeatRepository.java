package com.bookmyseat.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.bookmyseat.entity.SeatEntity;

public interface SeatRepository extends JpaRepository<SeatEntity, Long> {

	List<SeatEntity> findByShowIdOrderBySeatLabelAsc(UUID showId);

	@Query("SELECT s.status, COUNT(s) FROM SeatEntity s WHERE s.showId = :showId GROUP BY s.status")
	List<Object[]> countByStatus(UUID showId);

	@Query("SELECT s.status, COUNT(s) FROM SeatEntity s GROUP BY s.status")
	List<Object[]> countAllByStatus();

	/**
	 * Contested lock read: every requested seat, sorted, locked. Sorted order
	 * is the global lock order — overlapping requests lock identically.
	 */
	@Query(value = "SELECT * FROM seats WHERE show_id = :showId AND seat_label IN (:labels)"
			+ " ORDER BY seat_label FOR UPDATE", nativeQuery = true)
	List<SeatEntity> lockSeats(UUID showId, List<String> labels);

	@Query(value = "SELECT * FROM seats WHERE reservation_id = :reservationId"
			+ " ORDER BY seat_label FOR UPDATE", nativeQuery = true)
	List<SeatEntity> lockByReservation(UUID reservationId);

	/**
	 * Bulk creation in one round trip: labels arrive as a JSON array string
	 * (robust for any label text, unlike array binding or CSV).
	 */
	@Modifying
	@Query(value = "INSERT INTO seats (show_id, seat_label, status)"
			+ " SELECT :showId, x, 'available'"
			+ " FROM jsonb_array_elements_text(CAST(:labelsJson AS jsonb)) AS x", nativeQuery = true)
	int bulkInsert(UUID showId, String labelsJson);

	/** Guarded write: affected rows must equal the request size. */
	@Modifying
	@Query(value = "UPDATE seats SET status='confirmed', reservation_id=:reservationId,"
			+ " user_id=:userId, updated_at=now()"
			+ " WHERE show_id=:showId AND seat_label IN (:labels) AND status='available'", nativeQuery = true)
	int confirmSeats(UUID showId, List<String> labels, UUID reservationId, String userId);

	/** Guarded release: only seats still tied to this reservation move. */
	@Modifying
	@Query(value = "UPDATE seats SET status='available', reservation_id=NULL, user_id=NULL, updated_at=now()"
			+ " WHERE reservation_id=:reservationId AND status IN ('held','confirmed')", nativeQuery = true)
	int releaseSeats(UUID reservationId);
}
