package com.bookmyseat.observability;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Readiness probe (G13): {@code SELECT 1} with a short timeout so a wedged
 * database fails closed within seconds instead of hanging the check.
 */
@Service
public class HealthService {

	private final JdbcTemplate jdbc;

	public HealthService(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.jdbc.setQueryTimeout(2);
	}

	public boolean isDbUp() {
		try {
			Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
			return one != null && one == 1;
		}
		catch (Exception e) {
			return false;
		}
	}
}
