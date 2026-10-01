package com.bookmyseat.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.bookmyseat.service.JwtService;

import io.jsonwebtoken.JwtException;

class JwtServiceTest {

	private static final String SECRET = "test-secret-with-at-least-32-bytes!!";

	@Test
	void roundTripUser() {
		JwtService service = new JwtService(SECRET, 3600);
		String token = service.createToken("alice", "USER");
		AuthPrincipal principal = service.parse(token);
		assertThat(principal.userId()).isEqualTo("alice");
		assertThat(principal.role()).isEqualTo("USER");
	}

	@Test
	void roundTripAdmin() {
		JwtService service = new JwtService(SECRET, 3600);
		AuthPrincipal principal = service.parse(service.createToken("ops", "ADMIN"));
		assertThat(principal.isAdmin()).isTrue();
	}

	@Test
	void tamperedTokenRejected() {
		JwtService service = new JwtService(SECRET, 3600);
		String token = service.createToken("alice", "USER");
		String tampered = token.substring(0, token.length() - 2) + "xx";
		assertThatThrownBy(() -> service.parse(tampered)).isInstanceOf(JwtException.class);
	}

	@Test
	void wrongSecretRejected() {
		String token = new JwtService(SECRET, 3600).createToken("alice", "USER");
		JwtService other = new JwtService("another-secret-with-at-least-32-bytes!", 3600);
		assertThatThrownBy(() -> other.parse(token)).isInstanceOf(JwtException.class);
	}

	@Test
	void expiredTokenRejected() {
		JwtService service = new JwtService(SECRET, -1);
		String token = service.createToken("alice", "USER");
		assertThatThrownBy(() -> service.parse(token)).isInstanceOf(JwtException.class);
	}

	@Test
	void shortSecretRejected() {
		assertThatThrownBy(() -> new JwtService("short", 3600)).isInstanceOf(IllegalArgumentException.class);
	}
}
