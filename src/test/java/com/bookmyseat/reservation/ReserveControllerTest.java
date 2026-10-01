package com.bookmyseat.reservation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bookmyseat.controller.ReservationController;
import com.bookmyseat.security.AuthPrincipal;
import com.bookmyseat.service.JwtService;
import com.bookmyseat.service.ReservationService;
import com.bookmyseat.web.ApiException;

/** DB-free slice: service is mocked, security chain is real. */
@WebMvcTest(ReservationController.class)
@Import(com.bookmyseat.security.SecurityConfig.class)
@TestPropertySource(properties = "app.admin.token=test-admin-token")
class ReserveControllerTest {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	ReservationService reservations;

	@MockitoBean
	JwtService jwtService;

	private static String body() {
		return """
				{"seats":["A12"],"idempotency_key":"k-1"}
				""";
	}

	@Test
	void anonymousIs401() throws Exception {
		mvc.perform(post("/shows/{id}/reserve", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
				.content(body())).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
	}

	@Test
	void userReserveIs201WithEcho() throws Exception {
		UUID showId = UUID.randomUUID();
		UUID reservationId = UUID.randomUUID();
		when(jwtService.parse("user-token")).thenReturn(new AuthPrincipal("alice", "USER"));
		when(reservations.reserve(eq(showId), eq(List.of("A12")), any(),
				org.mockito.ArgumentMatchers.isNull(), eq(new AuthPrincipal("alice", "USER"))))
				.thenReturn(new ReserveOutcome(new ReserveResponse(reservationId, showId, "alice", List.of("A12"),
						25000L, "confirmed"), false));
		mvc.perform(post("/shows/{id}/reserve", showId).contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", "Bearer user-token").content(body()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.reservation_id").value(reservationId.toString()))
				.andExpect(jsonPath("$.user_id").value("alice"))
				.andExpect(jsonPath("$.seats[0]").value("A12"))
				.andExpect(jsonPath("$.amount_paise").value(25000))
				.andExpect(jsonPath("$.status").value("confirmed"));
	}

	@Test
	void seatTakenMapsTo409() throws Exception {
		UUID showId = UUID.randomUUID();
		when(jwtService.parse("user-token")).thenReturn(new AuthPrincipal("alice", "USER"));
		when(reservations.reserve(any(), any(), any(), any(), any())).thenThrow(
				new ApiException(HttpStatus.CONFLICT, "SEAT_TAKEN", "One or more requested seats are no longer available."));
		mvc.perform(post("/shows/{id}/reserve", showId).contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", "Bearer user-token").content(body()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("SEAT_TAKEN"));
	}

	@Test
	void conflictingKeysMapTo400() throws Exception {
		UUID showId = UUID.randomUUID();
		when(jwtService.parse("user-token")).thenReturn(new AuthPrincipal("alice", "USER"));
		when(reservations.reserve(any(), any(), any(), any(), any()))
				.thenThrow(ApiException.badRequest("VALIDATION_ERROR", "Conflicting idempotency keys in header and body."));
		mvc.perform(post("/shows/{id}/reserve", showId).contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", "Bearer user-token").header("Idempotency-Key", "other").content(body()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	@Test
	void replayIs200WithHeaderAndSameBody() throws Exception {
		UUID showId = UUID.randomUUID();
		UUID reservationId = UUID.randomUUID();
		when(jwtService.parse("user-token")).thenReturn(new AuthPrincipal("alice", "USER"));
		when(reservations.reserve(any(), any(), any(), any(), any()))
				.thenReturn(new ReserveOutcome(new ReserveResponse(reservationId, showId, "alice", List.of("A12"),
						25000L, "confirmed"), true));
		mvc.perform(post("/shows/{id}/reserve", showId).contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", "Bearer user-token").content(body()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reservation_id").value(reservationId.toString()))
				.andExpect(jsonPath("$.user_id").value("alice"))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
						.header().string("Idempotent-Replayed", "true"));
	}

	@Test
	void malformedShowIdIs400() throws Exception {
		when(jwtService.parse("user-token")).thenReturn(new AuthPrincipal("alice", "USER"));
		mvc.perform(post("/shows/{id}/reserve", "not-a-uuid").contentType(MediaType.APPLICATION_JSON)
				.header("Authorization", "Bearer user-token").content(body()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	@Test
	void cancelAnonymousIs401() throws Exception {
		mvc.perform(post("/reservations/{id}/cancel", UUID.randomUUID()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
	}

	@Test
	void cancelHappyIs200() throws Exception {
		UUID reservationId = UUID.randomUUID();
		UUID showId = UUID.randomUUID();
		when(jwtService.parse("user-token")).thenReturn(new AuthPrincipal("alice", "USER"));
		when(reservations.cancel(eq(reservationId), eq(new AuthPrincipal("alice", "USER"))))
				.thenReturn(new CancelResponse(reservationId, showId, "alice", List.of("A12"), "cancelled"));
		mvc.perform(post("/reservations/{id}/cancel", reservationId)
				.header("Authorization", "Bearer user-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reservation_id").value(reservationId.toString()))
				.andExpect(jsonPath("$.status").value("cancelled"));
	}

	@Test
	void cancelByNonOwnerIs403() throws Exception {
		UUID reservationId = UUID.randomUUID();
		when(jwtService.parse("user-token")).thenReturn(new AuthPrincipal("bob", "USER"));
		when(reservations.cancel(any(), any())).thenThrow(
				new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Only the owner can cancel."));
		mvc.perform(post("/reservations/{id}/cancel", reservationId)
				.header("Authorization", "Bearer user-token"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	@Test
	void cancelUnknownIs404() throws Exception {
		UUID reservationId = UUID.randomUUID();
		when(jwtService.parse("user-token")).thenReturn(new AuthPrincipal("alice", "USER"));
		when(reservations.cancel(any(), any())).thenThrow(
				new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Unknown reservation."));
		mvc.perform(post("/reservations/{id}/cancel", reservationId)
				.header("Authorization", "Bearer user-token"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}
}
