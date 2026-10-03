package com.bookmyseat.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code POST /shows} body. {@code price_paise} and {@code per_user_limit}
 * stay {@link JsonNode} so decimals and strings are rejected explicitly
 * (Jackson scalar coercion would otherwise silently accept {@code "25000"}).
 * Unknown fields are ignored for forward compatibility.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateShowRequest(String name, List<String> seats,
		@JsonProperty("price_paise") JsonNode pricePaise,
		@JsonProperty("per_user_limit") JsonNode perUserLimit) {
}
