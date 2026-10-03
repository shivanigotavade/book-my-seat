package com.bookmyseat.show;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bookmyseat.controller.ShowController;
import com.bookmyseat.domain.ShowDetailResponse;
import com.bookmyseat.domain.ShowResponse;
import com.bookmyseat.security.AuthPrincipal;
import com.bookmyseat.service.JwtService;
import com.bookmyseat.service.ShowService;
import com.bookmyseat.exception.handler.ApiException;

/**
 * DB-free slice: service is mocked, security chain is real. Proves the G4
 * contract (201 shape, 400 mapping) and the G3 access rules for this route
 * (401 anonymous, 403 USER, 201 ADMIN).
 */
@WebMvcTest(ShowController.class)
@Import(com.bookmyseat.security.SecurityConfig.class)
@TestPropertySource(properties = "app.admin.token=test-admin-token")
class ShowControllerTest {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	ShowService shows;

	@MockitoBean
	JwtService jwtService;

	private static String showJson() {
		return """
				{"name":"Rock Night","seats":["A1","A2"],"price_paise":25000}
				""";
	}

	@Test
	void anonymousIs401() throws Exception {
		mvc.perform(post("/shows").contentType(MediaType.APPLICATION_JSON).content(showJson()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
	}

	@Test
	void userRoleIs403() throws Exception {
		when(jwtService.parse("user-token")).thenReturn(new AuthPrincipal("alice", "USER"));
		mvc.perform(post("/shows").contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", "Bearer user-token").content(showJson()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	@Test
	void adminStaticTokenCreates201() throws Exception {
		UUID id = UUID.randomUUID();
		when(shows.createShow(any())).thenReturn(new ShowResponse(id, "Rock Night", 25000L, 4, 2,
				List.of(new ShowResponse.SeatItem("A1", "available"), new ShowResponse.SeatItem("A2", "available"))));
		mvc.perform(post("/shows").contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", "Bearer test-admin-token").content(showJson()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.name").value("Rock Night"))
				.andExpect(jsonPath("$.price_paise").value(25000))
				.andExpect(jsonPath("$.per_user_limit").value(4))
				.andExpect(jsonPath("$.total_seats").value(2))
				.andExpect(jsonPath("$.seats[0].seat").value("A1"))
				.andExpect(jsonPath("$.seats[0].status").value("available"));
	}

	@Test
	void serviceValidationMapsTo400() throws Exception {
		when(shows.createShow(any())).thenThrow(ApiException.badRequest("VALIDATION_ERROR", "duplicate seat labels"));
		mvc.perform(post("/shows").contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", "Bearer test-admin-token").content(showJson()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	@Test
	void malformedJsonIs400() throws Exception {
		mvc.perform(post("/shows").contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", "Bearer test-admin-token").content("{not json"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	@Test
	void invalidTokenIs401() throws Exception {
		when(jwtService.parse("bad-token")).thenThrow(new io.jsonwebtoken.security.SignatureException("bad"));
		mvc.perform(post("/shows").contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", "Bearer bad-token").content(showJson()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
	}

	@Test
	void getShowIsPublicAndFull() throws Exception {
		UUID id = UUID.randomUUID();
		when(shows.getShow(id, false)).thenReturn(new ShowDetailResponse(id, "Rock Night", 25000L, 4, 2,
				new ShowDetailResponse.Counts(1, 0, 1),
				List.of(new ShowResponse.SeatItem("A1", "available"), new ShowResponse.SeatItem("A2", "confirmed"))));
		mvc.perform(get("/shows/{id}", id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.counts.available").value(1))
				.andExpect(jsonPath("$.counts.held").value(0))
				.andExpect(jsonPath("$.counts.confirmed").value(1))
				.andExpect(jsonPath("$.seats.length()").value(2));
	}

	@Test
	void getShowSummaryOmitsSeats() throws Exception {
		UUID id = UUID.randomUUID();
		when(shows.getShow(id, true)).thenReturn(new ShowDetailResponse(id, "Rock Night", 25000L, 4, 2,
				new ShowDetailResponse.Counts(2, 0, 0), null));
		mvc.perform(get("/shows/{id}", id).param("summary", "true"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.counts.available").value(2))
				.andExpect(jsonPath("$.seats").doesNotExist());
	}

	@Test
	void getUnknownShowIs404() throws Exception {
		UUID id = UUID.randomUUID();
		when(shows.getShow(id, false))
				.thenThrow(new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Unknown show."));
		mvc.perform(get("/shows/{id}", id))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}
}
