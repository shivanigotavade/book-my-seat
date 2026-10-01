package com.bookmyseat.web;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Uniform non-2xx body per §7. Auth errors (G3) already use it; the global
 * {@code @RestControllerAdvice} in G11 will route every other domain failure
 * through the same shape.
 */
public record ApiError(@JsonProperty("error") ErrorBody error) {

	public record ErrorBody(String code, String message, Object details,
			@JsonProperty("request_id") String requestId) {
	}

	public static ApiError of(String code, String message, String requestId) {
		return of(code, message, Map.of(), requestId);
	}

	public static ApiError of(String code, String message, Object details, String requestId) {
		return new ApiError(new ErrorBody(code, message, details == null ? Map.of() : details, requestId));
	}
}
