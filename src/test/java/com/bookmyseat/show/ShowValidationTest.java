package com.bookmyseat.show;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.bookmyseat.validator.ShowRequestValidator;
import com.bookmyseat.exception.handler.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

class ShowValidationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	private static JsonNode json(String raw) {
		try {
			return JSON.readTree(raw);
		}
		catch (Exception e) {
			throw new IllegalArgumentException(e);
		}
	}

	@Test
	void happyPathTrimsAndDefaultsLimit() {
		var validated = ShowRequestValidator.validate("  Rock Night  ", List.of(" A1 ", "A2"),
				json("25000"), null);
		assertThat(validated.name()).isEqualTo("Rock Night");
		assertThat(validated.labels()).containsExactly("A1", "A2");
		assertThat(validated.pricePaise()).isEqualTo(25000L);
		assertThat(validated.perUserLimit()).isEqualTo(4);
	}

	@Test
	void blankNameRejected() {
		assertThatThrownBy(
				() -> ShowRequestValidator.validate("   ", List.of("A1"), json("100"), null))
				.isInstanceOf(ApiException.class).hasMessageContaining("name");
	}

	@Test
	void emptySeatsRejected() {
		assertThatThrownBy(() -> ShowRequestValidator.validate("Show", List.of(), json("100"), null))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void duplicateLabelsAfterTrimRejected() {
		assertThatThrownBy(
				() -> ShowRequestValidator.validate("Show", List.of("A1", " A1"), json("100"), null))
				.isInstanceOf(ApiException.class).hasMessageContaining("duplicate");
	}

	@Test
	void blankLabelRejected() {
		assertThatThrownBy(
				() -> ShowRequestValidator.validate("Show", new ArrayList<>(List.of("A1", "  ")), json("100"), null))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void longLabelRejected() {
		assertThatThrownBy(() -> ShowRequestValidator.validate("Show", List.of("A".repeat(33)), json("100"), null))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void tooManySeatsRejected() {
		List<String> seats = new ArrayList<>(ShowRequestValidator.MAX_SEATS + 1);
		for (int i = 0; i <= ShowRequestValidator.MAX_SEATS; i++) {
			seats.add("S" + i);
		}
		assertThatThrownBy(() -> ShowRequestValidator.validate("Show", seats, json("100"), null))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void decimalPriceRejected() {
		assertThatThrownBy(
				() -> ShowRequestValidator.validate("Show", List.of("A1"), json("25000.5"), null))
				.isInstanceOf(ApiException.class).hasMessageContaining("price_paise");
	}

	@Test
	void stringPriceRejected() {
		assertThatThrownBy(
				() -> ShowRequestValidator.validate("Show", List.of("A1"), json("\"25000\""), null))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void zeroAndNegativePriceRejected() {
		assertThatThrownBy(
				() -> ShowRequestValidator.validate("Show", List.of("A1"), json("0"), null))
				.isInstanceOf(ApiException.class);
		assertThatThrownBy(
				() -> ShowRequestValidator.validate("Show", List.of("A1"), json("-5"), null))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void overflowingPriceRejected() {
		JsonNode huge = JsonNodeFactory.instance.numberNode(new BigInteger("9223372036854775808"));
		assertThatThrownBy(() -> ShowRequestValidator.validate("Show", List.of("A1"), huge, null))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void perUserLimitBounds() {
		assertThat(ShowRequestValidator.validate("S", List.of("A1"), json("10"), json("2")).perUserLimit())
				.isEqualTo(2);
		assertThatThrownBy(() -> ShowRequestValidator.validate("S", List.of("A1"), json("10"), json("0")))
				.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> ShowRequestValidator.validate("S", List.of("A1"), json("10"), json("\"4\"")))
				.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> ShowRequestValidator.validate("S", List.of("A1"), json("10"), json("2.5")))
				.isInstanceOf(ApiException.class);
	}
}
