package com.bookmyseat.observability;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bookmyseat.controller.HealthController;
import com.bookmyseat.security.JwtService;

/** No auth header anywhere: both probes are public (G13). */
@WebMvcTest(HealthController.class)
@Import(com.bookmyseat.security.SecurityConfig.class)
class HealthControllerTest {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	HealthService health;

	@MockitoBean
	JwtService jwtService;

	@Test
	void liveIsUp() throws Exception {
		mvc.perform(get("/health/live")).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void readyUpIs200() throws Exception {
		when(health.isDbUp()).thenReturn(true);
		mvc.perform(get("/health/ready")).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"))
				.andExpect(jsonPath("$.db").value("UP"));
	}

	@Test
	void readyDownIs503() throws Exception {
		when(health.isDbUp()).thenReturn(false);
		mvc.perform(get("/health/ready")).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value("DOWN"))
				.andExpect(jsonPath("$.db").value("DOWN"));
	}
}
