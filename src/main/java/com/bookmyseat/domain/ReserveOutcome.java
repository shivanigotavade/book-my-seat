package com.bookmyseat.domain;

/**
 * Result of a reserve call. First-time reservations are {@code replayed=false}
 * (controller answers {@code 201}); same-key retries return the stored
 * reservation with {@code replayed=true} (controller answers {@code 200} +
 * {@code Idempotent-Replayed: true}), keeping "exactly one 201 per hot seat".
 */
public record ReserveOutcome(ReserveResponse response, boolean replayed) {
}
