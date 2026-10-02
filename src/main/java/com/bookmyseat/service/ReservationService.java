package com.bookmyseat.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.bookmyseat.common.TransactionRetry;
import com.bookmyseat.entity.ReservationEntity;
import com.bookmyseat.entity.ReservationSeatEntity;
import com.bookmyseat.entity.SeatEntity;
import com.bookmyseat.idempotency.RequestHasher;
import com.bookmyseat.observability.ReservationMetrics;
import com.bookmyseat.repository.IdempotencyKeyRepository;
import com.bookmyseat.repository.ReservationRepository;
import com.bookmyseat.repository.ReservationSeatRepository;
import com.bookmyseat.repository.SeatRepository;
import com.bookmyseat.repository.ShowRepository;
import com.bookmyseat.repository.UserShowQuotaRepository;
import com.bookmyseat.reservation.CancelResponse;
import com.bookmyseat.reservation.ReserveOutcome;
import com.bookmyseat.reservation.ReserveRequestValidator;
import com.bookmyseat.reservation.ReserveResponse;
import com.bookmyseat.security.AuthPrincipal;
import com.bookmyseat.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Atomic reserve core (G5). One {@code READ COMMITTED} transaction:
 *
 * <ol>
 * <li>validate + normalise (trim, de-duplicate, <b>sort</b>);</li>
 * <li>idempotency gate (G7): same-transaction key insert; replays return the
 * stored reservation before touching quota or seats;</li>
 * <li>fast {@code per_user_limit} pre-check (the concurrent gate is G6);</li>
 * <li>conditional quota increment (G6) — serialises same-user parallels;</li>
 * <li>lock every requested seat with {@code SELECT … ORDER BY seat_label
 * FOR UPDATE} — concurrent transactions queue behind the first;</li>
 * <li>re-check status while holding the locks, then a guarded
 * {@code UPDATE … WHERE status='available'} (belt and braces);</li>
 * <li>insert the reservation + {@code reservation_seats} safety-net rows and
 * link the idempotency key.</li>
 * </ol>
 *
 * <p>Global lock order (G8): idempotency row (G7) → quota row (G6) →
 * seats sorted by label → reservation row. The decision is taken while
 * holding a row lock on every requested seat, so 500 racers on one seat
 * produce exactly one {@code 201}. Declines roll back cleanly as 4xx.
 *
 * <p>Multi-seat policy (G8) is all-or-nothing: any unavailable seat declines
 * the whole request, nothing is held and quota is untouched. Because every
 * request normalises to sorted order, overlapping pairs like
 * {@code [A1,A2]} and {@code [A2,A1]} lock identically and cannot deadlock;
 * residual transient contention (deadlock/serialization/lock-timeout) is
 * retried outside the transaction boundary, up to 3 attempts, then
 * {@code 429} — never a 5xx.
 *
 * <p>Cancel (G9) follows the same order — quota → seats sorted →
 * reservation — and frees seats only while tied to the cancelled
 * reservation, so re-booked seats are never resurrected.
 */
@Service
public class ReservationService {

	private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

	private final ShowRepository shows;
	private final SeatRepository seatRepository;
	private final ReservationRepository reservations;
	private final ReservationSeatRepository seatRows;
	private final UserShowQuotaRepository quotas;
	private final IdempotencyKeyRepository keys;
	private final TransactionTemplate tx;
	private final TransactionRetry retry;
	private final ReservationMetrics metrics;
	/** Show config is immutable after creation — cached, never invalidated. */
	private final ConcurrentHashMap<UUID, ShowConfig> showCache = new ConcurrentHashMap<>();

	public ReservationService(ShowRepository shows, SeatRepository seatRepository,
			ReservationRepository reservations,
			ReservationSeatRepository seatRows, UserShowQuotaRepository quotas, IdempotencyKeyRepository keys,
			PlatformTransactionManager txManager, ReservationMetrics metrics) {
		this.shows = shows;
		this.seatRepository = seatRepository;
		this.reservations = reservations;
		this.seatRows = seatRows;
		this.quotas = quotas;
		this.keys = keys;
		this.tx = new TransactionTemplate(txManager);
		this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.metrics = metrics;
		this.retry = new TransactionRetry(3, 25, metrics::retried);
	}

	public ReserveOutcome reserve(UUID showId, List<String> seats, JsonNode bodyKey, String headerKey,
			AuthPrincipal caller) {
		String keyPreview = previewKey(bodyKey, headerKey);
		decisionMdc(showId, caller.userId(), keyPreview);
		try {
			ReserveOutcome outcome = retry
					.run(() -> tx.execute(status -> reserveTx(showId, seats, bodyKey, headerKey, caller)));
			if (outcome.replayed()) {
				metrics.replayed();
				log.info("reservation.replayed seats={}", outcome.response().seats().size());
			}
			else {
				metrics.confirmed();
				log.info("reservation.confirmed reservation_id={} seats={} amount_paise={}",
						outcome.response().reservationId(), outcome.response().seats().size(),
						outcome.response().amountPaise());
			}
			return outcome;
		}
		catch (ApiException e) {
			metrics.declined(e.getCode());
			log.info("reservation.declined reason={}", e.getCode());
			throw e;
		}
		finally {
			clearDecisionMdc();
		}
	}

	private ReserveOutcome reserveTx(UUID showId, List<String> seats, JsonNode bodyKey, String headerKey,
			AuthPrincipal caller) {
		String idempotencyKey = ReserveRequestValidator.resolveKey(bodyKey, headerKey);
		List<String> labels = ReserveRequestValidator.normalizeSeats(seats);
		String requestHash = RequestHasher.hash(showId, labels);

		// Idempotency gate (G7): first statement. A concurrent same-key
		// transaction blocks on this row until the first commits or aborts, so
		// there is no window for a double reserve. A declined first attempt
		// rolls back and frees the key.
		if (keys.insertIgnore(caller.userId(), showId, idempotencyKey, requestHash) == 0) {
			return replay(caller.userId(), showId, idempotencyKey, requestHash);
		}

		ShowConfig config = showCache.computeIfAbsent(showId, id -> shows.findById(id)
				.map(s -> new ShowConfig(s.getPricePaise(), s.getPerUserLimit()))
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Unknown show.")));
		long pricePaise = config.pricePaise();
		int perUserLimit = config.perUserLimit();

		if (labels.size() > perUserLimit) {
			throw new ApiException(HttpStatus.CONFLICT, "PER_USER_LIMIT",
					"Request exceeds per_user_limit of " + perUserLimit + ".");
		}
		// Quota gate (G6): one conditional upsert takes the quota row lock,
		// serialising parallel requests from the same user. It sits before the
		// seat locks per the global order (quota → seats) and rolls back with
		// the transaction when the seat step declines, so failed attempts
		// consume nothing. Idempotent replays (G7) return before this point
		// and never increment again.
		if (quotas.upsertIncrementIfFits(caller.userId(), showId, labels.size(), perUserLimit) == 0) {
			throw new ApiException(HttpStatus.CONFLICT, "PER_USER_LIMIT",
					"Would exceed per_user_limit of " + perUserLimit + ".");
		}

		List<SeatEntity> locked = seatRepository.lockSeats(showId, labels);

		if (locked.size() != labels.size()) {
			var found = locked.stream().map(SeatEntity::getSeatLabel).toList();
			var missing = labels.stream().filter(l -> !found.contains(l)).toList();
			throw new ApiException(HttpStatus.NOT_FOUND, "INVALID_SEAT",
					"Unknown seats for this show.", Map.of("seats", missing));
		}
		var taken = locked.stream().filter(r -> !"available".equals(r.getStatus())).map(SeatEntity::getSeatLabel)
				.toList();
		if (!taken.isEmpty()) {
			throw new ApiException(HttpStatus.CONFLICT, "SEAT_TAKEN",
					"One or more requested seats are no longer available.", Map.of("seats", taken));
		}

		long amount;
		try {
			amount = Math.multiplyExact(pricePaise, labels.size());
		}
		catch (ArithmeticException e) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "amount_paise overflows.");
		}

		UUID reservationId = UUID.randomUUID();
		// Flushed now (not at commit): the guarded seat update below carries
		// a foreign key to this row, and native queries execute immediately
		// while entity inserts otherwise wait for flush.
		reservations.saveAndFlush(new ReservationEntity(reservationId, showId, caller.userId(), amount));
		if (seatRepository.confirmSeats(showId, labels, reservationId, caller.userId()) != labels.size()) {
			throw new ApiException(HttpStatus.CONFLICT, "SEAT_TAKEN",
					"One or more requested seats are no longer available.", Map.of("seats", labels));
		}

		seatRows.saveAll(labels.stream().map(label -> new ReservationSeatEntity(reservationId, showId, label))
				.toList());
		keys.linkReservation(caller.userId(), showId, idempotencyKey, reservationId);

		return new ReserveOutcome(new ReserveResponse(reservationId, showId, caller.userId(),
				new ArrayList<>(labels), amount, "confirmed"), false);
	}

	/**
	 * Same key seen before: mismatched fingerprint → 409, otherwise the stored
	 * reservation with no seat, quota or counter movement.
	 */
	private ReserveOutcome replay(String userId, UUID showId, String idempotencyKey, String requestHash) {
		var stored = keys.findByUserIdAndShowIdAndIdempotencyKey(userId, showId, idempotencyKey);
		if (stored.isEmpty()) {
			// Defensive: the conflicting row vanished (first attempt aborted
			// after we observed the conflict). Re-insert and proceed as first-timer.
			keys.insertIgnore(userId, showId, idempotencyKey, requestHash);
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RETRY_LATER",
					"Concurrent reservation in progress; retry with the same key.");
		}
		if (!requestHash.equals(stored.get().getRequestHash())) {
			throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
					"Idempotency key was already used with a different request.");
		}
		UUID reservationId = stored.get().getReservationId();
		if (reservationId == null) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RETRY_LATER",
					"Concurrent reservation in progress; retry with the same key.");
		}
		ReservationEntity reservation = reservations.findById(reservationId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Original reservation is gone."));
		List<String> seats = seatRows.findLabels(reservationId);
		return new ReserveOutcome(new ReserveResponse(reservationId, reservation.getShowId(),
				reservation.getUserId(), seats, reservation.getAmountPaise(), reservation.getStatus()), true);
	}

	/**
	 * Owner-only cancel (G9). Follows the global lock order — quota →
	 * seats (sorted) → reservation — and releases seats only while they are
	 * still tied to <b>this</b> reservation, so a late duplicate cancel can
	 * never free a seat that was re-booked by someone else. Repeat cancel is
	 * {@code 200} with no double decrement.
	 */
	public CancelResponse cancel(UUID reservationId, AuthPrincipal caller) {
		decisionMdc(null, caller.userId(), null);
		try {
			for (int i = 0; i < 3; i++) {
				try {
					CancelResponse response = retry.run(() -> tx.execute(status -> cancelTx(reservationId, caller)));
					metrics.cancelled();
					metrics.released(response.seats().size());
					log.info("reservation.cancelled reservation_id={} seats={}", reservationId,
							response.seats().size());
					return response;
				}
				catch (CancelConflictException e) {
					// Lost a concurrent-cancel race after touching quota; the whole
					// attempt rolled back, so re-reading is safe (next pass sees
					// cancelled → idempotent 200).
				}
				catch (ApiException e) {
					metrics.declined(e.getCode());
					log.info("reservation.declined reason={}", e.getCode());
					throw e;
				}
			}
			return readCancelState(reservationId, caller);
		}
		finally {
			clearDecisionMdc();
		}
	}

	private CancelResponse cancelTx(UUID reservationId, AuthPrincipal caller) {
		ReservationEntity reservation = reservations.findById(reservationId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Unknown reservation."));
		UUID showId = reservation.getShowId();
		String owner = reservation.getUserId();
		if (!caller.userId().equals(owner)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the owner can cancel.");
		}
		if ("cancelled".equals(reservation.getStatus())) {
			return cancelState(reservationId, showId, owner);
		}

		// Lock-free label read for the quota count; the quota UPDATE below
		// takes the quota lock before any seat lock (global order).
		List<String> labels = seatRows.findLabels(reservationId);
		quotas.decrement(owner, showId, labels.size());

		List<String> locked = seatRepository.lockByReservation(reservationId).stream()
				.map(SeatEntity::getSeatLabel)
				.toList();

		if (reservations.cancelReservation(reservationId, owner) == 0) {
			// A concurrent cancel committed first; quota/seat moves above roll
			// back with this attempt — retry to return the idempotent 200.
			throw new CancelConflictException();
		}

		// Guarded by reservation_id: seats re-booked to a new owner since are untouched.
		seatRepository.releaseSeats(reservationId);
		seatRows.deactivate(reservationId);

		return new CancelResponse(reservationId, showId, owner, locked, "cancelled");
	}

	private CancelResponse readCancelState(UUID reservationId, AuthPrincipal caller) {
		ReservationEntity reservation = reservations.findById(reservationId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Unknown reservation."));
		if (!caller.userId().equals(reservation.getUserId())) {
			throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the owner can cancel.");
		}
		return cancelState(reservationId, reservation.getShowId(), reservation.getUserId());
	}

	private CancelResponse cancelState(UUID reservationId, UUID showId, String owner) {
		return new CancelResponse(reservationId, showId, owner, seatRows.findLabels(reservationId), "cancelled");
	}

	/** Internal: lost a concurrent-cancel race; the attempt rolled back — re-read instead. */
	private static final class CancelConflictException extends RuntimeException {
	}

	private record ShowConfig(long pricePaise, int perUserLimit) {
	}

	/**
	 * Per-decision MDC (G15): {@code show_id} and a truncated key preview join
	 * the {@code request_id}/{@code user_id} set upstream, so any line is
	 * greppable from the error response. Removed afterwards — threads are reused.
	 */
	private static void decisionMdc(UUID showId, String userId, String keyPreview) {
		if (showId != null) {
			MDC.put("show_id", showId.toString());
		}
		if (userId != null) {
			MDC.put("user_id", userId);
		}
		if (keyPreview != null) {
			MDC.put("idempotency_key", keyPreview);
		}
	}

	private static void clearDecisionMdc() {
		MDC.remove("show_id");
		MDC.remove("idempotency_key");
		MDC.remove("outcome");
	}

	private static String previewKey(JsonNode bodyKey, String headerKey) {
		String raw = headerKey != null && !headerKey.isBlank() ? headerKey.trim()
				: bodyKey != null && bodyKey.isTextual() ? bodyKey.textValue().trim() : null;
		if (raw == null || raw.isEmpty()) {
			return null;
		}
		return raw.length() <= 12 ? raw : raw.substring(0, 12);
	}
}
