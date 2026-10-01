package com.bookmyseat.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.bookmyseat.observability.RequestIdFilter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

/**
 * Minimal uniform error mapping (G4 slice; expanded to the full zero-5xx
 * policy in G11). Every handled failure returns the §7 {@link ApiError} body.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest request) {
		String requestId = RequestIdFilter.resolve(request);
		return ResponseEntity.status(ex.getStatus())
				.body(ApiError.of(ex.getCode(), ex.getMessage(), ex.getDetails(), requestId));
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
			MethodArgumentTypeMismatchException.class })
	public ResponseEntity<ApiError> handleBadBody(Exception ex, HttpServletRequest request) {
		String requestId = RequestIdFilter.resolve(request);
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(ApiError.of("VALIDATION_ERROR", "Malformed or invalid request body.", requestId));
	}
}
