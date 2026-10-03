package com.bookmyseat.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /auth/token} body. Token issuance request.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TokenRequest(
		@NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "invalid user_id") String user_id,
		String role) {
}
