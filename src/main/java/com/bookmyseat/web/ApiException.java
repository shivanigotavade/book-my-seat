package com.bookmyseat.web;

import org.springframework.http.HttpStatus;

/** Domain failure mapped to a non-2xx {@link ApiError} by the global handler. */
public class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;
	private final Object details;

	public ApiException(HttpStatus status, String code, String message) {
		this(status, code, message, null);
	}

	public ApiException(HttpStatus status, String code, String message, Object details) {
		super(message);
		this.status = status;
		this.code = code;
		this.details = details;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}

	public Object getDetails() {
		return details;
	}

	public static ApiException badRequest(String code, String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, code, message);
	}
}
