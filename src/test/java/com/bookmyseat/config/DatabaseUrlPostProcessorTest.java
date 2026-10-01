package com.bookmyseat.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DatabaseUrlPostProcessorTest {

	@Test
	void providerUrlBecomesJdbcWithEmbeddedCredentials() {
		var normalized = DatabaseUrlPostProcessor
				.normalize("postgres://bookmyseat:s3cr3t@db.internal:5432/bookmyseat?sslmode=require");
		assertThat(normalized.jdbcUrl()).isEqualTo("jdbc:postgresql://db.internal:5432/bookmyseat?sslmode=require");
		assertThat(normalized.username()).isEqualTo("bookmyseat");
		assertThat(normalized.password()).isEqualTo("s3cr3t");
	}

	@Test
	void jdbcAndBlankAreLeftAlone() {
		assertThat(DatabaseUrlPostProcessor.normalize("jdbc:postgresql://localhost:5432/bookmyseat")).isNull();
		assertThat(DatabaseUrlPostProcessor.normalize(null)).isNull();
		assertThat(DatabaseUrlPostProcessor.normalize("  ")).isNull();
		assertThat(DatabaseUrlPostProcessor.normalize("mysql://x")).isNull();
	}

	@Test
	void percentEncodedCredentialsDecoded() {
		var normalized = DatabaseUrlPostProcessor.normalize("postgres://u%40x:p%40ss@h:5432/db");
		assertThat(normalized.username()).isEqualTo("u@x");
		assertThat(normalized.password()).isEqualTo("p@ss");
	}

	@Test
	void postProcessorPutsNormalizedFirst() {
		var env = new MockEnvironment().withProperty("DATABASE_URL", "postgres://u:p@h:5432/db");
		new DatabaseUrlPostProcessor().postProcessEnvironment(env, null);
		assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://h:5432/db");
		assertThat(env.getProperty("spring.datasource.username")).isEqualTo("u");
		assertThat(env.getProperty("spring.datasource.password")).isEqualTo("p");
	}

	@Test
	void garbageUrlFailsFast() {
		assertThatThrownBy(() -> DatabaseUrlPostProcessor.normalize("postgres://[bad"))
				.isInstanceOf(IllegalStateException.class);
	}
}
