package com.bookmyseat.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code POST /shows/{id}/reserve} body. Carries no identity — any
 * {@code user_id} field sent by the caller is ignored (never bound) and the
 * reservation owner always comes from the token principal.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReserveRequest(List<String> seats,
		@JsonProperty("idempotency_key") JsonNode idempotencyKey) {
}
