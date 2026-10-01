package com.bookmyseat.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Resolves the caller from {@code Authorization: Bearer <token>} (G3).
 * <ul>
 * <li>Static {@code ADMIN_TOKEN} (env) authenticates as {@code admin/ADMIN}
 * for show creation without a JWT round-trip.</li>
 * <li>Otherwise the token is verified as HS256 JWT via {@link JwtService}.</li>
 * <li>Missing token: the request continues anonymous (public paths stay
 * open; protected paths yield 401 from the entry point).</li>
 * <li>Invalid token: the context is cleared and the request continues so the
 * entry point — not a 500 — decides.</li>
 * </ul>
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	static final String AUTHORIZATION = "Authorization";
	static final String BEARER = "Bearer ";

	private final JwtService jwtService;
	private final byte[] adminTokenBytes;

	public JwtAuthenticationFilter(JwtService jwtService, String adminToken) {
		this.jwtService = jwtService;
		this.adminTokenBytes = adminToken == null ? new byte[0]
				: adminToken.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
			@NonNull FilterChain chain) throws ServletException, IOException {
		String token = resolveBearer(request);
		if (token == null) {
			chain.doFilter(request, response);
			return;
		}
		try {
			AuthPrincipal principal = authenticate(token);
			var auth = new UsernamePasswordAuthenticationToken(principal, null,
					List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
			SecurityContextHolder.getContext().setAuthentication(auth);
		}
		catch (JwtException | IllegalArgumentException e) {
			SecurityContextHolder.clearContext();
		}
		chain.doFilter(request, response);
	}

	private AuthPrincipal authenticate(String token) throws JwtException {
		if (adminTokenBytes.length > 0
				&& MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), adminTokenBytes)) {
			return new AuthPrincipal("admin", "ADMIN");
		}
		return jwtService.parse(token);
	}

	private static String resolveBearer(HttpServletRequest request) {
		String header = request.getHeader(AUTHORIZATION);
		if (header == null || !header.startsWith(BEARER) || header.length() <= BEARER.length()) {
			return null;
		}
		String token = header.substring(BEARER.length()).trim();
		return token.isEmpty() ? null : token;
	}
}
