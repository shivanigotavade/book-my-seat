package com.bookmyseat.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Maps {@code shows}. DDL owned by Flyway; this is a read/write view only. */
@Entity
@Table(name = "shows")
public class ShowEntity {

	@Id
	@Column(name = "id")
	private UUID id;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "price_paise", nullable = false)
	private long pricePaise;

	@Column(name = "per_user_limit", nullable = false)
	private int perUserLimit;

	@Column(name = "total_seats", nullable = false)
	private int totalSeats;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected ShowEntity() {
	}

	public ShowEntity(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats) {
		this.id = id;
		this.name = name;
		this.pricePaise = pricePaise;
		this.perUserLimit = perUserLimit;
		this.totalSeats = totalSeats;
	}

	public UUID getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public long getPricePaise() {
		return pricePaise;
	}

	public int getPerUserLimit() {
		return perUserLimit;
	}

	public int getTotalSeats() {
		return totalSeats;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
