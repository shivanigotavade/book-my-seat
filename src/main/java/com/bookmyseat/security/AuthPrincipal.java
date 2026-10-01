package com.bookmyseat.security;

/**
 * Authenticated caller identity. The user id always comes from the verified
 * token — never from a request body (G3). Controllers must read this from the
 * {@code SecurityContext}, never bind a {@code user_id} body field.
 *
 * @param userId token subject ({@code sub} claim)
 * @param role   {@code USER} or {@code ADMIN}
 */
public record AuthPrincipal(String userId, String role) {

	public boolean isAdmin() {
		return "ADMIN".equals(role);
	}
}
