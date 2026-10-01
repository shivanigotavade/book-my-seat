package com.bookmyseat.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.bookmyseat.observability.RequestIdFilter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

/**
 * Zero-5xx error policy (G11). Every domain outcome is a 4xx {@link ApiError};
 * contention exhaustion is {@code 429 + Retry-After}; only truly unexpected
 * bugs are 500 (generic message, full stack in structured logs only).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest request) {
		String requestId = RequestIdFilter.resolve(request);
		var builder = ResponseEntity.status(ex.getStatus());
		if (ex.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
			builder.header("Retry-After", "1");
		}
		if (ex.getStatus().is5xxServerError()) {
			log.error("unexpected domain failure code={} request_id={}", ex.getCode(), requestId, ex);
		}
		return builder.body(ApiError.of(ex.getCode(), ex.getMessage(), ex.getDetails(), requestId));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiError> handleBeanValidation(MethodArgumentNotValidException ex,
			HttpServletRequest request) {
		String requestId = RequestIdFilter.resolve(request);
		Map<String, String> fields = new LinkedHashMap<>();
		ex.getBindingResult().getFieldErrors().forEach(e -> fields.put(e.getField(), e.getDefaultMessage()));
		return ResponseEntity.badRequest().body(
				ApiError.of("VALIDATION_ERROR", "Validation failed.", Map.of("fields", fields), requestId));
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, ConstraintViolationException.class,
			MethodArgumentTypeMismatchException.class, BindException.class,
			MissingServletRequestParameterException.class })
	public ResponseEntity<ApiError> handleBadRequest(Exception ex, HttpServletRequest request) {
		String requestId = RequestIdFilter.resolve(request);
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(ApiError.of("VALIDATION_ERROR", "Malformed or invalid request.", requestId));
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
		String requestId = RequestIdFilter.resolve(request);
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(ApiError.of("NOT_FOUND", "Unknown endpoint.", requestId));
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ApiError> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
			HttpServletRequest request) {
		String requestId = RequestIdFilter.resolve(request);
		return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
				.body(ApiError.of("METHOD_NOT_ALLOWED", "HTTP method not supported here.", requestId));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
		String requestId = RequestIdFilter.resolve(request);
		log.error("unexpected failure request_id={}", requestId, ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ApiError.of("INTERNAL", "Unexpected failure.", requestId));
	}
}
