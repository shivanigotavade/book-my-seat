package com.bookmyseat.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Deploy support (G17). Managed providers (Render/Railway/Fly) hand out
 * {@code DATABASE_URL} as {@code postgres://user:pass@host:port/db?...}
 * while the driver needs {@code jdbc:postgresql://…}. This rewrites the
 * property before binding so the same image boots everywhere; plain JDBC
 * URLs pass through untouched and keep file/env credentials.
 */
public class DatabaseUrlPostProcessor implements EnvironmentPostProcessor {

	static final String SOURCE = "DATABASE_URL";
	static final String TARGET = "db-normalized";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		String raw = environment.getProperty(SOURCE);
		Normalized normalized = normalize(raw);
		if (normalized == null) {
			return;
		}
		Map<String, Object> props = new HashMap<>();
		props.put("spring.datasource.url", normalized.jdbcUrl());
		props.put("spring.datasource.username", normalized.username());
		props.put("spring.datasource.password", normalized.password());
		environment.getPropertySources().addFirst(new MapPropertySource(TARGET, props));
	}

	/** Returns null when there is nothing to normalize (absent or JDBC form). */
	static Normalized normalize(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String trimmed = raw.trim();
		if (trimmed.startsWith("jdbc:")) {
			return null;
		}
		if (!trimmed.startsWith("postgres://") && !trimmed.startsWith("postgresql://")) {
			return null;
		}
		try {
			URI uri = new URI(trimmed);
			String userInfo = uri.getUserInfo() == null ? "" : uri.getUserInfo();
			String user = userInfo;
			String pass = "";
			int sep = userInfo.indexOf(':');
			if (sep >= 0) {
				user = userInfo.substring(0, sep);
				pass = userInfo.substring(sep + 1);
			}
			var jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
			if (uri.getPort() != -1) {
				jdbc.append(':').append(uri.getPort());
			}
			jdbc.append(uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath());
			if (uri.getQuery() != null) {
				jdbc.append('?').append(uri.getQuery());
			}
			return new Normalized(jdbc.toString(), decode(user), decode(pass));
		}
		catch (Exception e) {
			throw new IllegalStateException("Invalid DATABASE_URL", e);
		}
	}

	private static String decode(String value) {
		return URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

	record Normalized(String jdbcUrl, String username, String password) {
	}
}
