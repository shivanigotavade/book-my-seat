package com.bookmyseat.exception.handler;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Verifies the G11 mapping table without a container or database. */
class GlobalExceptionHandlerTest {

	@RestController
	static class Stub {
		@GetMapping("/conflict")
		String conflict() {
			throw new ApiException(HttpStatus.CONFLICT, "SEAT_TAKEN", "Taken.");
		}

		@GetMapping("/busy")
		String busy() {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RETRY_LATER", "Busy.");
		}

		@GetMapping("/boom")
		String boom() {
			throw new IllegalStateException("broken invariant");
		}

		@PostMapping(value = "/echo", consumes = MediaType.APPLICATION_JSON_VALUE)
		String echo(@RequestBody Echo echo) {
			return echo.text();
		}

		record Echo(String text) {
		}
	}

	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Stub())
			.setControllerAdvice(new GlobalExceptionHandler()).build();

	@Test
	void domainConflictKeepsCode() throws Exception {
		mvc.perform(get("/conflict")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("SEAT_TAKEN"))
				.andExpect(jsonPath("$.error.request_id").isNotEmpty());
	}

	@Test
	void exhaustionCarriesRetryAfter() throws Exception {
		mvc.perform(get("/busy")).andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "1"))
				.andExpect(jsonPath("$.error.code").value("RETRY_LATER"));
	}

	@Test
	void unexpectedBugIs500WithoutLeak() throws Exception {
		mvc.perform(get("/boom")).andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.error.code").value("INTERNAL"))
				.andExpect(jsonPath("$.error.message").value("Unexpected failure."));
	}

	@Test
	void malformedJsonIs400() throws Exception {
		mvc.perform(post("/echo").contentType(MediaType.APPLICATION_JSON).content("{oops"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}
}
