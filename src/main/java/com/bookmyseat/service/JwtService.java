package com.bookmyseat.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.bookmyseat.security.AuthPrincipal;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Stateless HS256 JWT issuance and verification (G3).
 * Claims: {@code sub} = user id, {@code role} = USER/ADMIN, {@code exp}.
 */
@Service
public class JwtService {

	private final SecretKey key;
	private final long expirationSeconds;

	public JwtService(@Value("${app.jwt.secret}") String secret,
			@Value("${app.jwt.expiration-seconds:86400}") long expirationSeconds) {
		byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
		if (bytes.length < 32) {
			throw new IllegalArgumentException("app.jwt.secret must be at least 32 bytes for HS256");
		}
		this.key = Keys.hmacShaKeyFor(bytes);
		this.expirationSeconds = expirationSeconds;
	}

	public String createToken(String userId, String role) {
		Instant now = Instant.now();
		return Jwts.builder()
				.subject(userId)
				.claim("role", role)
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plusSeconds(expirationSeconds)))
				.signWith(key)
				.compact();
	}

	/**
	 * Verifies signature + expiry and returns the caller identity.
	 *
	 * @throws JwtException on invalid/expired token or unexpected role claim
	 */
	public AuthPrincipal parse(String token) throws JwtException {
		var claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
		String userId = claims.getSubject();
		String role = claims.get("role", String.class);
		if (userId == null || userId.isBlank()) {
			throw new JwtException("missing sub claim");
		}
		if (!"USER".equals(role) && !"ADMIN".equals(role)) {
			throw new JwtException("unexpected role claim");
		}
		return new AuthPrincipal(userId, role);
	}

	public long getExpirationSeconds() {
		return expirationSeconds;
	}
}
