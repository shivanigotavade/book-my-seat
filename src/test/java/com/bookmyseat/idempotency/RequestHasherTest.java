package com.bookmyseat.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RequestHasherTest {

	@Test
	void deterministicAndScoped() {
		UUID show = UUID.randomUUID();
		String a = RequestHasher.hash(show, List.of("A12"));
		assertThat(RequestHasher.hash(show, List.of("A12"))).isEqualTo(a);
		assertThat(a).hasSize(64).matches("[0-9a-f]+");
		assertThat(RequestHasher.hash(show, List.of("A13"))).isNotEqualTo(a);
		assertThat(RequestHasher.hash(UUID.randomUUID(), List.of("A12"))).isNotEqualTo(a);
		assertThat(RequestHasher.hash(show, List.of("A12", "A13")))
				.isNotEqualTo(RequestHasher.hash(show, List.of("A13", "A12")));
	}
}
