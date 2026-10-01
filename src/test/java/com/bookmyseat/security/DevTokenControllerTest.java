package com.bookmyseat.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.bookmyseat.controller.DevTokenController;
import com.bookmyseat.service.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * DB-free slice: the controller is wired standalone so no DataSource/Flyway
 * is needed. Full chain (401/403 via {@link SecurityConfig}) is covered by
 * the Testcontainers security tests in G19.
 */
class DevTokenControllerTest {

	private static final String SECRET = "test-secret-with-at-least-32-bytes!!";

	private static MockMvc mockMvc(boolean enabled) {
		var controller = new DevTokenController(new JwtService(SECRET, 3600), enabled);
		return MockMvcBuilders.standaloneSetup(controller).build();
	}

	@Test
	void issuesUserTokenAndIgnoresRoleField() throws Exception {
		String body = mockMvc(true).perform(post("/auth/token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"user_id\":\"alice\",\"role\":\"ADMIN\"}"))
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
