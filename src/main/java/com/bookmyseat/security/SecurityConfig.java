package com.bookmyseat.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.bookmyseat.observability.RequestIdFilter;
import com.bookmyseat.service.JwtService;
import com.bookmyseat.web.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.DispatcherType;

/**
 * Token-derived identity (G3). Stateless, CSRF off, no sessions.
 *
 * <p>Access matrix:
 * <ul>
 * <li>{@code POST /auth/token}, {@code /health/**}, {@code /actuator/**},
 * {@code /metrics}, {@code /error} — public.</li>
 * <li>{@code GET /shows/**} — public (documented choice: show state must be
 * pollable during a burst without auth).</li>
 * <li>{@code POST /shows} — {@code ADMIN} only.</li>
 * <li>{@code POST /shows/{id}/reserve}, {@code POST /reservations/{id}/cancel} —
 * any authenticated caller ({@code USER} or {@code ADMIN}); ownership is
 * enforced in the service layer from the token principal.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	private final JwtService jwtService;
	private final String adminToken;
	private final ObjectMapper objectMapper;

	public SecurityConfig(JwtService jwtService, @Value("${app.admin.token:}") String adminToken,
			ObjectMapper objectMapper) {
		this.jwtService = jwtService;
		this.adminToken = adminToken;
		this.objectMapper = objectMapper;
	}

	@Bean
	JwtAuthenticationFilter jwtAuthenticationFilter() {
		return new JwtAuthenticationFilter(jwtService, adminToken);
	}

	@Bean
	org.springframework.boot.web.servlet.FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(
			JwtAuthenticationFilter filter) {
		var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}

	@Bean
	AuthenticationEntryPoint authenticationEntryPoint() {
		return (request, response, ex) -> {
			if (response.isCommitted()) {
				return;
			}
			String requestId = RequestIdFilter.resolve(request);
			response.setStatus(HttpStatus.UNAUTHORIZED.value());
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setHeader(RequestIdFilter.HEADER, requestId);
			objectMapper.writeValue(response.getWriter(),
					ApiError.of("UNAUTHENTICATED", "Missing or invalid token.", requestId));
		};
	}

	@Bean
	AccessDeniedHandler accessDeniedHandler() {
		return (request, response, ex) -> {
			if (response.isCommitted()) {
				return;
			}
			String requestId = RequestIdFilter.resolve(request);
			response.setStatus(HttpStatus.FORBIDDEN.value());
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setHeader(RequestIdFilter.HEADER, requestId);
			objectMapper.writeValue(response.getWriter(),
					ApiError.of("FORBIDDEN", "Insufficient permissions.", requestId));
		};
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter,
			AuthenticationEntryPoint entryPoint, AccessDeniedHandler deniedHandler) throws Exception {
		http.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.httpBasic(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))
				.authorizeHttpRequests(auth -> auth
						.dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
						.requestMatchers(HttpMethod.POST, "/auth/token").permitAll()
						.requestMatchers("/health/**", "/actuator/**", "/metrics", "/error").permitAll()
						.requestMatchers(HttpMethod.GET, "/shows/**").permitAll()
						.requestMatchers(HttpMethod.POST, "/shows").hasRole("ADMIN")
						.requestMatchers(HttpMethod.POST, "/shows/*/reserve").hasAnyRole("USER", "ADMIN")
						.requestMatchers(HttpMethod.POST, "/reservations/*/cancel").hasAnyRole("USER", "ADMIN")
						.anyRequest().authenticated())
				.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}
}
