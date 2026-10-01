package com.bookmyseat.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

/**
 * Request fingerprint for idempotency (G7): {@code SHA-256(show_id |
 * sorted seats)}. Same key + same fingerprint replays the stored
 * reservation; same key + different fingerprint is
 * {@code 409 IDEMPOTENCY_KEY_REUSED}.
 */
public final class RequestHasher {

	private RequestHasher() {
	}

	public static String hash(UUID showId, List<String> sortedLabels) {
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			String input = showId + "|" + String.join(",", sortedLabels);
			byte[] digest = sha.digest(input.getBytes(StandardCharsets.UTF_8));
			var hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16));
				hex.append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
