package com.bookmyseat.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import com.bookmyseat.web.ApiException;

class TransactionRetryTest {

	@Test
	void stateMapping() {
		assertThat(TransactionRetry.isTransient(
				new DeadlockLoserDataAccessException("deadlock", new SQLException("d", "40P01")))).isTrue();
		assertThat(TransactionRetry.isTransient(new RuntimeException(new SQLException("s", "40001")))).isTrue();
		assertThat(TransactionRetry.isTransient(new RuntimeException(new SQLException("l", "55P03")))).isTrue();
		assertThat(TransactionRetry.isTransient(new CannotGetJdbcConnectionException("pool"))).isTrue();
		assertThat(TransactionRetry.isTransient(new DuplicateKeyException("dup"))).isFalse();
		assertThat(TransactionRetry.isTransient(new IllegalStateException("bug"))).isFalse();
		assertThat(TransactionRetry.isTransient(new RuntimeException(new SQLException("x", "23505")))).isFalse();
	}

	@Test
	void retriesTransientThenSucceeds() {
		var retry = new TransactionRetry(3, 1);
		var calls = new AtomicInteger();
		String result = retry.run(() -> {
			if (calls.incrementAndGet() < 3) {
				throw new DeadlockLoserDataAccessException("deadlock", new SQLException("d", "40P01"));
			}
			return "ok";
		});
		assertThat(result).isEqualTo("ok");
		assertThat(calls.get()).isEqualTo(3);
	}

	@Test
	void domainFailuresPassThroughUntouched() {
		var retry = new TransactionRetry(3, 1);
		var failure = new ApiException(org.springframework.http.HttpStatus.CONFLICT, "SEAT_TAKEN", "taken");
		var calls = new AtomicInteger();
		assertThatThrownBy(() -> retry.run(() -> {
			calls.incrementAndGet();
			throw failure;
		})).isSameAs(failure);
		assertThat(calls.get()).isEqualTo(1);
	}

	@Test
	void exhaustionBecomes429() {
		var retry = new TransactionRetry(3, 1);
		var calls = new AtomicInteger();
		assertThatThrownBy(() -> retry.run(() -> {
			calls.incrementAndGet();
			throw new DeadlockLoserDataAccessException("deadlock", new SQLException("d", "40P01"));
		})).isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.getCode()).isEqualTo("RETRY_LATER"));
		assertThat(calls.get()).isEqualTo(3);
	}
}
