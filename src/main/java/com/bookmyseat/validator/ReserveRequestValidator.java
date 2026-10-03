package com.bookmyseat.validator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;

import com.bookmyseat.exception.handler.ApiException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Pure validation/normalisation for the reserve path (G5). DB-free so it is
 * unit-testable.
 */
public final class ReserveRequestValidator {

	private ReserveRequestValidator() {
	}

	/**
	 * Trims, drops nothing, de-duplicates and sorts ascending. Sorted order is
	 * the global lock order (G8): every transaction locks seats in the same
	 * sequence, so overlapping multi-seat requests cannot deadlock.
	 */
	public static List<String> normalizeSeats(List<String> seats) {
		if (seats == null || seats.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "seats must not be empty.");
		}
		Set<String> unique = new LinkedHashSet<>();
		for (String raw : seats) {
			String label = raw == null ? "" : raw.trim();
			if (label.isEmpty()) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "seat labels must not be blank.");
			}
			if (label.length() > 32) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
						"seat label '" + label + "' exceeds 32 characters.");
			}
			unique.add(label);
		}
		var sorted = new ArrayList<>(unique);
		sorted.sort(String::compareTo);
		return List.copyOf(sorted);
	}

	/**
	 * Key source: {@code Idempotency-Key} header or {@code idempotency_key}
	 * body field. Both present with different values → 400; neither → 400.
	 * The returned key is validated (non-blank text, ≤ 128 chars, printable).
	 * G7 persists it; G5 only requires its presence.
	 */
	public static String resolveKey(JsonNode bodyKey, String headerKey) {
		String body = bodyKey == null || bodyKey.isNull() ? null : extractKey(bodyKey);
		String header = headerKey == null || headerKey.isBlank() ? null : headerKey.trim();
		if (body != null && header != null && !body.equals(header)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
					"Conflicting idempotency keys in header and body.");
		}
		String key = header != null ? header : body;
		if (key == null || key.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
					"idempotency_key is required (body field or Idempotency-Key header).");
		}
		return checkKey(key);
	}

	private static String extractKey(JsonNode node) {
		if (!node.isTextual()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
					"idempotency_key must be a string.");
		}
		String value = node.textValue().trim();
		return value.isEmpty() ? null : value;
	}

	private static String checkKey(String key) {
		if (key.length() > 128 || key.chars().anyMatch(Character::isISOControl)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
					"idempotency_key must be printable and at most 128 characters.");
		}
		return key;
	}
}
