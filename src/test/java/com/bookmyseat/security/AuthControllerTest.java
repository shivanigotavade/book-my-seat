package com.bookmyseat.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.bookmyseat.controller.AuthController;
import com.bookmyseat.service.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * DB-free slice: the controller is wired standalone so no DataSource/Flyway
 * is needed. Full chain (401/403 via {@link SecurityConfig}) is covered by
 * the Testcontainers security tests in G19.
 */
class AuthControllerTest {

	private static final String SECRET = "test-secret-with-at-least-32-bytes!!";
	private static final String BOOTSTRAP = "test-bootstrap-secret";

	private static MockMvc mockMvc(boolean enabled) {
		var controller = new AuthController(new JwtService(SECRET, 3600), enabled, BOOTSTRAP);
		return MockMvcBuilders.standaloneSetup(controller).build();
	}

	@Test
	void issuesUserTokenByDefault() throws Exception {
		String body = mockMvc(true).perform(post("/auth/token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"user_id\":\"alice\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.user_id").value("alice"))
				.andExpect(jsonPath("$.role").value("USER"))
				.andReturn().getResponse().getContentAsString();

		JsonNode token = new ObjectMapper().readTree(body).get("token");
		assertThat(token.asText()).isNotBlank();
		AuthPrincipal principal = new JwtService(SECRET, 3600).parse(token.asText());
		assertThat(principal).isEqualTo(new AuthPrincipal("alice", "USER"));
	}

	@Test
	void adminRoleWithoutSecretIs401() throws Exception {
		mockMvc(true).perform(post("/auth/token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"user_id\":\"alice\",\"role\":\"ADMIN\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
	}

	@Test
	void adminRoleWithBootstrapSecretIssuesAdmin() throws Exception {
		String body = mockMvc(true).perform(post("/auth/token")
				.contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", "Bearer " + BOOTSTRAP)
				.content("{\"user_id\":\"ops\",\"role\":\"ADMIN\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("ADMIN"))
				.andReturn().getResponse().getContentAsString();

		JsonNode token = new ObjectMapper().readTree(body).get("token");
		AuthPrincipal principal = new JwtService(SECRET, 3600).parse(token.asText());
		assertThat(principal).isEqualTo(new AuthPrincipal("ops", "ADMIN"));
	}

	@Test
	void adminRoleWithWrongSecretIs401() throws Exception {
		mockMvc(true).perform(post("/auth/token")
				.contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", "Bearer wrong-secret")
				.content("{\"user_id\":\"ops\",\"role\":\"ADMIN\"}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void adminIssuanceIgnoresDisabledFlag() throws Exception {
		mockMvc(false).perform(post("/auth/token")
				.contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", "Bearer " + BOOTSTRAP)
				.content("{\"user_id\":\"ops\",\"role\":\"admin\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("ADMIN"));
	}

	@Test
	void unknownRoleIs400() throws Exception {
		mockMvc(true).perform(post("/auth/token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"user_id\":\"alice\",\"role\":\"SUPER\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	@Test
	void invalidUserIdIs400() throws Exception {
		mockMvc(true).perform(post("/auth/token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"user_id\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	@Test
	void disabledEndpointIs404() throws Exception {
		mockMvc(false).perform(post("/auth/token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"user_id\":\"alice\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}
}
