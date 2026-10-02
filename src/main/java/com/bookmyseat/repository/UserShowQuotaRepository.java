package com.bookmyseat.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.bookmyseat.entity.UserShowQuotaEntity;

public interface UserShowQuotaRepository extends JpaRepository<UserShowQuotaEntity, UserShowQuotaEntity.PK> {

	/**
	 * Conditional increment: the row lock serialises same-user parallels;
	 * zero rows means the limit would break. Single statement: inserts the
	 * row when absent (the caller pre-checks n &lt;= limit) or increments
	 * when it fits.
	 */
	@Modifying
	@Query(value = "INSERT INTO user_show_quota (user_id, show_id, active_seats) VALUES (:userId, :showId, :n)"
			+ " ON CONFLICT (user_id, show_id) DO UPDATE SET active_seats = user_show_quota.active_seats + :n"
			+ " WHERE user_show_quota.active_seats + :n <= :limit", nativeQuery = true)
	int upsertIncrementIfFits(String userId, UUID showId, int n, int limit);

	@Modifying
	@Query(value = "UPDATE user_show_quota SET active_seats = active_seats - :n"
			+ " WHERE user_id=:userId AND show_id=:showId", nativeQuery = true)
	int decrement(String userId, UUID showId, int n);
}
