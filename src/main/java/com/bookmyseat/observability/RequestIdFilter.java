package com.bookmyseat.observability;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Minimal request correlation (G3 slice of G15). Takes {@code X-Request-Id}
 * when supplied, else generates one; echoes it in the response header,
 * exposes it as a request attribute for error bodies, and puts it in the MDC.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";
	public static final String ATTRIBUTE = "request_id";
	public static final String MDC_KEY = "request_id";

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
			@NonNull FilterChain chain) throws ServletException, IOException {
		String requestId = request.getHeader(HEADER);
		if (requestId == null || requestId.isBlank()) {
			requestId = UUID.randomUUID().toString();
		}
		else {
			requestId = requestId.trim();
		}
		request.setAttribute(ATTRIBUTE, requestId);
		response.setHeader(HEADER, requestId);
		MDC.put(MDC_KEY, requestId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(MDC_KEY);
		}
	}

	/** Request id for error bodies: filter attribute, else inbound header, else generated. */
	public static String resolve(HttpServletRequest request) {
		Object attr = request == null ? null : request.getAttribute(ATTRIBUTE);
		if (attr instanceof String s && !s.isBlank()) {
			return s;
		}
		String header = request == null ? null : request.getHeader(HEADER);
		if (header != null && !header.isBlank()) {
			return header.trim();
		}
		return UUID.randomUUID().toString();
	}
}
