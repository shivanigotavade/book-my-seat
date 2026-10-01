package com.bookmyseat.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.bookmyseat.entity.IdempotencyKeyEntity;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyEntity, IdempotencyKeyEntity.PK> {

	/**
	 * First statement of the reserve transaction. A concurrent same-key
	 * transaction blocks here until the first commits or aborts.
	 */
	@Modifying
	@Query(value = "INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash)"
			+ " VALUES (:userId, :key, :hash) ON CONFLICT (user_id, idempotency_key) DO NOTHING",
			nativeQuery = true)
	int insertIgnore(String userId, String key, String hash);

	Optional<IdempotencyKeyEntity> findByUserIdAndIdempotencyKey(String userId, String key);

	@Modifying
	@Query(value = "UPDATE idempotency_keys SET reservation_id = :reservationId"
			+ " WHERE user_id=:userId AND idempotency_key=:key", nativeQuery = true)
	int linkReservation(String userId, String key, UUID reservationId);
}
