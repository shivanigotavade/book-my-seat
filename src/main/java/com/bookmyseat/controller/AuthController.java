package com.bookmyseat.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bookmyseat.domain.ApiError;
import com.bookmyseat.domain.TokenRequest;
import com.bookmyseat.domain.TokenResponse;
import com.bookmyseat.observability.RequestIdFilter;
import com.bookmyseat.service.JwtService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Token issuance. Open issuance is USER-only and flagged by
 * {@code AUTH_DEV_TOKEN_ENDPOINT_ENABLED} (disabled → 404). ADMIN elevation
 * additionally requires the bootstrap {@code ADMIN_TOKEN} secret as a bearer:
 * possession of the server secret mints a short-lived ADMIN JWT, so the
 * static secret itself rarely travels and every admin call is a verified
 * JWT. The static token keeps working directly as a fallback.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

	private final JwtService jwtService;
	private final boolean tokenEndpointEnabled;
	private final byte[] adminTokenBytes;

	public AuthController(JwtService jwtService,
			@Value("${app.auth.dev-token-endpoint-enabled:true}") boolean tokenEndpointEnabled,
			@Value("${app.admin.token:}") String adminToken) {
		this.jwtService = jwtService;
		this.tokenEndpointEnabled = tokenEndpointEnabled;
		this.adminTokenBytes = adminToken == null ? new byte[0]
				: adminToken.getBytes(StandardCharsets.UTF_8);
	}

	@PostMapping("/token")
	public ResponseEntity<?> token(@RequestBody(required = false) TokenRequest body,
			@RequestHeader(value = "Authorization", required = false) String authorization,
			HttpServletRequest request) {
		String requestId = RequestIdFilter.resolve(request);
		String role = body == null || body.role() == null ? "USER" : body.role().trim().toUpperCase();
		if (!"USER".equals(role) && !"ADMIN".equals(role)) {
			return ResponseEntity.badRequest().body(
					ApiError.of("VALIDATION_ERROR", "role must be USER or ADMIN.", requestId));
		}
		if ("ADMIN".equals(role)) {
			if (!isBootstrapAdmin(authorization)) {
				return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
						ApiError.of("UNAUTHENTICATED", "ADMIN issuance requires the bootstrap admin secret.",
								requestId));
			}
		}
		else if (!tokenEndpointEnabled) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(ApiError.of("NOT_FOUND", "Unknown endpoint.", requestId));
		}
		String userId = body == null || body.user_id() == null ? "" : body.user_id().trim();
		if (userId.isEmpty() || userId.length() > 64 || !userId.matches("^[A-Za-z0-9._-]+$")) {
			return ResponseEntity.badRequest()
					.body(ApiError.of("VALIDATION_ERROR", "user_id must be 1..64 chars [A-Za-z0-9._-].", requestId));
		}
		String token = jwtService.createToken(userId, role);
		return ResponseEntity
				.ok(new TokenResponse(token, "Bearer", userId, role, jwtService.getExpirationSeconds()));
	}

	private boolean isBootstrapAdmin(String authorization) {
		if (authorization == null || adminTokenBytes.length == 0) {
			return false;
		}
		String prefix = "Bearer ";
		if (!authorization.startsWith(prefix)) {
			return false;
		}
		String presented = authorization.substring(prefix.length()).trim();
		if (presented.isEmpty()) {
			return false;
		}
		return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), adminTokenBytes);
	}
}
