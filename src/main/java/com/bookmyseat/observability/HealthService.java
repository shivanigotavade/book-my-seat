package com.bookmyseat.observability;

import org.springframework.stereotype.Service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Readiness probe (G13): {@code SELECT 1} with a short timeout so a wedged
 * database fails closed within seconds instead of hanging the check.
 */
@Service
public class HealthService {

	@PersistenceContext
	private EntityManager entities;

	public boolean isDbUp() {
		try {
			Object one = entities.createNativeQuery("SELECT 1")
					.setHint("jakarta.persistence.query.timeout", 2000).getSingleResult();
			return ((Number) one).intValue() == 1;
		}
		catch (Exception e) {
			return false;
		}
	}
}
