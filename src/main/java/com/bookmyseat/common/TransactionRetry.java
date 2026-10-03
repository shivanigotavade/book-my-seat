package com.bookmyseat.common;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import com.bookmyseat.exception.handler.ApiException;

/**
 * Bounded retry for transient contention (G8). Retries SQL states
 * {@code 40P01} (deadlock), {@code 40001} (serialization failure) and
 * {@code 55P03} (lock timeout) — plus pool/lock acquisition failures — up to
 * 3 attempts with jittered backoff, <b>outside</b> the transaction boundary
 * (each attempt gets a fresh transaction). Exhaustion becomes
 * {@code 429 RETRY_LATER}, never a 5xx.
 */
public final class TransactionRetry {

	static final Set<String> TRANSIENT_STATES = Set.of("40P01", "40001", "55P03");

	private final int maxAttempts;
	private final long baseBackoffMillis;
	private final Consumer<String> retryListener;

	public TransactionRetry() {
		this(3, 25, cause -> {
		});
	}

	TransactionRetry(int maxAttempts, long baseBackoffMillis) {
		this(maxAttempts, baseBackoffMillis, cause -> {
		});
	}

	public TransactionRetry(int maxAttempts, long baseBackoffMillis, Consumer<String> retryListener) {
		this.maxAttempts = maxAttempts;
		this.baseBackoffMillis = baseBackoffMillis;
		this.retryListener = retryListener;
	}

	public <T> T run(Supplier<T> work) {
		int attempt = 0;
		for (;;) {
			attempt++;
			try {
				return work.get();
			}
			catch (RuntimeException e) {
				if (!isTransient(e)) {
					throw e;
				}
				if (attempt >= maxAttempts) {
					throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RETRY_LATER",
							"Contention; retry with the same key.");
				}
				retryListener.accept(causeOf(e));
				sleep(attempt);
			}
		}
	}

	static boolean isTransient(Throwable t) {
		return causeOf(t) != null;
	}

	private static String causeOf(Throwable t) {
		boolean pool = false;
		for (Throwable cur = t; cur != null; cur = cur.getCause()) {
			if (cur instanceof CannotGetJdbcConnectionException || cur instanceof CannotAcquireLockException) {
				pool = true;
			}
			if (cur instanceof java.sql.SQLException sql && sql.getSQLState() != null
					&& TRANSIENT_STATES.contains(sql.getSQLState())) {
				return switch (sql.getSQLState()) {
				case "40P01" -> "deadlock";
				case "40001" -> "serialization";
				default -> "lock-timeout";
				};
			}
		}
		return pool ? "pool" : null;
	}

	private void sleep(int attempt) {
		long delay = baseBackoffMillis * attempt + ThreadLocalRandom.current().nextLong(baseBackoffMillis);
		try {
			Thread.sleep(delay);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RETRY_LATER",
					"Contention; retry with the same key.");
		}
	}
}
