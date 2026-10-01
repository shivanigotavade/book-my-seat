package com.bookmyseat.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.bookmyseat.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

class ReserveValidationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Test
	void normalizesTrimsDedupsSorts() {
		assertThat(ReserveRequestValidator.normalizeSeats(List.of("B2", "A1", "B2 ")))
				.containsExactly("A1", "B2");
	}

	@Test
	void emptyAndBlankSeatsRejected() {
		assertThatThrownBy(() -> ReserveRequestValidator.normalizeSeats(List.of()))
				.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> ReserveRequestValidator.normalizeSeats(null)).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> ReserveRequestValidator.normalizeSeats(List.of("A1", " ")))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void headerKeyUsedWhenBodyAbsent() {
		assertThat(ReserveRequestValidator.resolveKey(null, "k-1")).isEqualTo("k-1");
	}

	@Test
	void bodyKeyUsedWhenHeaderAbsent() throws Exception {
		assertThat(ReserveRequestValidator.resolveKey(JSON.readTree("\"k-2\""), null)).isEqualTo("k-2");
	}

	@Test
	void sameKeyInBothPlacesAccepted() throws Exception {
		assertThat(ReserveRequestValidator.resolveKey(JSON.readTree("\"k\""), "k")).isEqualTo("k");
	}

	@Test
	void differingKeysRejected() throws Exception {
		assertThatThrownBy(() -> ReserveRequestValidator.resolveKey(JSON.readTree("\"a\""), "b"))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void missingKeyRejected() {
		assertThatThrownBy(() -> ReserveRequestValidator.resolveKey(null, null)).isInstanceOf(ApiException.class);
		assertThatThrownBy(
				() -> ReserveRequestValidator.resolveKey(JsonNodeFactory.instance.nullNode(), "  "))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void nonStringAndOversizedKeysRejected() throws Exception {
		assertThatThrownBy(() -> ReserveRequestValidator.resolveKey(JSON.readTree("123"), null))
				.isInstanceOf(ApiException.class);
		assertThatThrownBy(
				() -> ReserveRequestValidator.resolveKey(JSON.readTree("\"" + "k".repeat(129) + "\""), null))
				.isInstanceOf(ApiException.class);
	}
}
