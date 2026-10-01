package com.bookmyseat.reservation;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UserShowQuotaRepository extends JpaRepository<UserShowQuotaEntity, UserShowQuotaEntity.PK> {

	@Modifying
	@Query(value = "INSERT INTO user_show_quota (user_id, show_id, active_seats) VALUES (:userId, :showId, 0)"
			+ " ON CONFLICT (user_id, show_id) DO NOTHING", nativeQuery = true)
	int upsertZero(String userId, UUID showId);

	/**
	 * Conditional increment: the row lock serialises same-user parallels;
	 * zero rows means the limit would break.
	 */
	@Modifying
	@Query(value = "UPDATE user_show_quota SET active_seats = active_seats + :n"
			+ " WHERE user_id=:userId AND show_id=:showId AND active_seats + :n <= :limit", nativeQuery = true)
	int incrementIfFits(String userId, UUID showId, int n, int limit);

	@Modifying
	@Query(value = "UPDATE user_show_quota SET active_seats = active_seats - :n"
			+ " WHERE user_id=:userId AND show_id=:showId", nativeQuery = true)
	int decrement(String userId, UUID showId, int n);
}
