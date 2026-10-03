package com.bookmyseat.validator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;

import com.bookmyseat.exception.handler.ApiException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Pure validation/normalisation for show creation (G4). DB-free so it is
 * unit-testable; {@link ShowService} calls it inside the write transaction.
 */
public final class ShowRequestValidator {

	public static final int MAX_SEATS = 100_000;
	public static final int MAX_LABEL_LENGTH = 32;
	public static final int MAX_NAME_LENGTH = 200;
	public static final int DEFAULT_PER_USER_LIMIT = 4;
	public static final int MAX_PER_USER_LIMIT = 1_000;

	private ShowRequestValidator() {
	}

	public record ValidatedShow(String name, List<String> labels, long pricePaise, int perUserLimit) {
	}

	public static ValidatedShow validate(String name, List<String> seats, JsonNode pricePaise, JsonNode perUserLimit) {
		String cleanName = name == null ? "" : name.trim();
		if (cleanName.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "name must not be blank.");
		}
		if (cleanName.length() > MAX_NAME_LENGTH) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
					"name must be at most " + MAX_NAME_LENGTH + " characters.");
		}
		if (seats == null || seats.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "seats must not be empty.");
		}
		if (seats.size() > MAX_SEATS) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
					"seats must contain at most " + MAX_SEATS + " labels.");
		}
		Set<String> unique = new LinkedHashSet<>();
		List<String> duplicates = new ArrayList<>();
		for (String raw : seats) {
			String label = raw == null ? "" : raw.trim();
			if (label.isEmpty()) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "seat labels must not be blank.");
			}
			if (label.length() > MAX_LABEL_LENGTH) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
						"seat label '" + label + "' exceeds " + MAX_LABEL_LENGTH + " characters.");
			}
			if (!unique.add(label)) {
				duplicates.add(label);
			}
		}
		if (!duplicates.isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
					"duplicate seat labels: " + String.join(", ", duplicates));
		}
		long price = requirePositiveLong(pricePaise, "price_paise");
		int limit = perUserLimit == null || perUserLimit.isNull() ? DEFAULT_PER_USER_LIMIT
				: requireRangeInt(perUserLimit, "per_user_limit", 1, MAX_PER_USER_LIMIT);
		return new ValidatedShow(cleanName, List.copyOf(unique), price, limit);
	}

	private static long requirePositiveLong(JsonNode node, String field) {
		if (node == null || node.isNull() || !node.isIntegralNumber()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
					field + " must be a positive integer.");
		}
		long value;
		if (!node.canConvertToLong()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
					field + " must be a positive integer.");
		}
		value = node.longValue();
		if (value <= 0) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
					field + " must be a positive integer.");
		}
		return value;
	}

	private static int requireRangeInt(JsonNode node, String field, int min, int max) {
		if (!node.isIntegralNumber() || !node.canConvertToInt()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
					field + " must be an integer between " + min + " and " + max + ".");
		}
		int value = node.intValue();
		if (value < min || value > max) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
					field + " must be an integer between " + min + " and " + max + ".");
		}
		return value;
	}
}
