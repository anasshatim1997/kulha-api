package ma.kulha.reservation.interfaces.dto;

import java.time.Instant;

public record PickupCredentialResponse(String qrPayload, String qrImageDataUrl, Instant expiresAt) { }
