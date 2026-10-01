package com.bookmyseat.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bookmyseat.observability.RequestIdFilter;
import com.bookmyseat.web.ApiError;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Dev token endpoint for load testing (G3). Flagged by
 * {@code AUTH_DEV_TOKEN_ENDPOINT_ENABLED}; when disabled the route is 404 so
 * reviewers can switch it off in prod. Always issues {@code USER} — any
 * {@code role} field in the body is ignored, never bound.
 */
@RestController
@RequestMapping("/auth")
public class DevTokenController {

	private final JwtService jwtService;
	private final boolean devEnabled;

	public DevTokenController(JwtService jwtService,
			@Value("${app.auth.dev-token-endpoint-enabled:true}") boolean devEnabled) {
		this.jwtService = jwtService;
		this.devEnabled = devEnabled;
	}

	/** Only {@code user_id} is read; unknown fields (e.g. {@code role}) are dropped. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record TokenRequest(
			@NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "invalid user_id") String user_id) {
	}

	public record TokenResponse(String token, @JsonProperty("token_type") String tokenType,
			@JsonProperty("user_id") String userId, String role, @JsonProperty("expires_in") long expiresIn) {
	}

	@PostMapping("/token")
	public ResponseEntity<?> token(@RequestBody(required = false) TokenRequest body,
			HttpServletRequest request) {
		String requestId = RequestIdFilter.resolve(request);
		if (!devEnabled) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(ApiError.of("NOT_FOUND", "Unknown endpoint.", requestId));
		}
		String userId = body == null || body.user_id() == null ? "" : body.user_id().trim();
		if (userId.isEmpty() || userId.length() > 64 || !userId.matches("^[A-Za-z0-9._-]+$")) {
			return ResponseEntity.badRequest()
					.body(ApiError.of("VALIDATION_ERROR", "user_id must be 1..64 chars [A-Za-z0-9._-].", requestId));
		}
		String token = jwtService.createToken(userId, "USER");
		return ResponseEntity.ok(new TokenResponse(token, "Bearer", userId, "USER", jwtService.getExpirationSeconds()));
	}
}
