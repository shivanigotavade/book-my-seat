package com.bookmyseat.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import com.bookmyseat.service.JwtService;

class JwtAuthenticationFilterTest {

	private static final String SECRET = "test-secret-with-at-least-32-bytes!!";
	private static final String ADMIN_TOKEN = "static-admin-token";

	private final JwtService jwtService = new JwtService(SECRET, 3600);

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
		MDC.clear();
	}

	private void doFilter(String authorization) throws Exception {
		var filter = new JwtAuthenticationFilter(jwtService, ADMIN_TOKEN);
		var request = new MockHttpServletRequest();
		if (authorization != null) {
			request.addHeader("Authorization", authorization);
		}
		filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
	}

	@Test
	void missingTokenStaysAnonymous() throws Exception {
		doFilter(null);
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test
	void validJwtAuthenticatesTokenUser() throws Exception {
		String token = jwtService.createToken("alice", "USER");
		doFilter("Bearer " + token);
		var auth = SecurityContextHolder.getContext().getAuthentication();
		assertThat(auth).isNotNull();
		assertThat(auth.getPrincipal()).isEqualTo(new AuthPrincipal("alice", "USER"));
		assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
	}

	@Test
	void invalidJwtClearsContext() throws Exception {
		doFilter("Bearer not-a-jwt");
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test
	void staticAdminTokenAuthenticatesAdmin() throws Exception {
		doFilter("Bearer " + ADMIN_TOKEN);
		var auth = SecurityContextHolder.getContext().getAuthentication();
		assertThat(auth).isNotNull();
		assertThat(auth.getPrincipal()).isEqualTo(new AuthPrincipal("admin", "ADMIN"));
		assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_ADMIN");
	}

	@Test
	void userIdIsInMdcDuringRequest() throws Exception {
		var filter = new JwtAuthenticationFilter(jwtService, ADMIN_TOKEN);
		var request = new MockHttpServletRequest();
		request.addHeader("Authorization", "Bearer " + jwtService.createToken("alice", "USER"));
		var seen = new AtomicReference<Map<String, String>>();
		filter.doFilter(request, new MockHttpServletResponse(),
				(req, res) -> seen.set(MDC.getCopyOfContextMap()));
		assertThat(seen.get()).containsEntry("user_id", "alice");
	}
}
