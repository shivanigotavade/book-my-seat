package com.bookmyseat.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

	private final RequestIdFilter filter = new RequestIdFilter();

	@AfterEach
	void clear() {
		MDC.clear();
	}

	@Test
	void generatesEchoesAndCleansUp() throws Exception {
		var request = new MockHttpServletRequest();
		var response = new MockHttpServletResponse();
		var mdcDuring = new AtomicReference<java.util.Map<String, String>>();
		filter.doFilter(request, response, (req, res) -> mdcDuring.set(MDC.getCopyOfContextMap()));

		String id = (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
		assertThat(id).isNotBlank();
		assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(id);
		assertThat(mdcDuring.get()).containsEntry(RequestIdFilter.MDC_KEY, id);
		assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
	}

	@Test
	void preservesSuppliedId() throws Exception {
		var request = new MockHttpServletRequest();
		request.addHeader(RequestIdFilter.HEADER, "req-123");
		var response = new MockHttpServletResponse();
		filter.doFilter(request, response, new MockFilterChain());

		assertThat(request.getAttribute(RequestIdFilter.ATTRIBUTE)).isEqualTo("req-123");
		assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("req-123");
		assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
	}
}
