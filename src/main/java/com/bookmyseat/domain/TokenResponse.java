package com.bookmyseat.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code POST /auth/token} response. Issued JWT with identity and expiry.
 */
public record TokenResponse(String token, @JsonProperty("token_type") String tokenType,
		@JsonProperty("user_id") String userId, String role, @JsonProperty("expires_in") long expiresIn) {
}
