package ma.kulha.reservation.application;

import java.time.Instant;

public record PickupCredential(String token, Instant expiresAt) { }
