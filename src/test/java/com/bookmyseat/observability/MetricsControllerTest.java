package com.bookmyseat.observability;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bookmyseat.controller.MetricsController;
import com.bookmyseat.security.JwtService;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/** Public exposition without auth (G14). */
@WebMvcTest(MetricsController.class)
@Import(com.bookmyseat.security.SecurityConfig.class)
class MetricsControllerTest {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	PrometheusMeterRegistry prometheus;

	@MockitoBean
	JwtService jwtService;

	@Test
	void exposesPrometheusText() throws Exception {
		when(prometheus.scrape()).thenReturn("# HELP bookmyseat_up 1\n# TYPE bookmyseat_up gauge\nbookmyseat_up 1\n");
		mvc.perform(get("/metrics")).andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("bookmyseat_up 1")));
	}
}
