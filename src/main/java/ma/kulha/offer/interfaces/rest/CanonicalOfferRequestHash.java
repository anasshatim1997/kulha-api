package ma.kulha.offer.interfaces.rest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import ma.kulha.offer.interfaces.dto.OfferRequest;

final class CanonicalOfferRequestHash {
    private CanonicalOfferRequestHash() { }

    static String duplicate(UUID offerId) {
        return digest("DUPLICATE:" + offerId);
    }

    static String create(OfferRequest request) {
        Map<String, String> fields = new TreeMap<>();
        fields.put("allergens", request.allergens() == null ? "" : request.allergens().stream().distinct().sorted().collect(java.util.stream.Collectors.joining(",")));
        fields.put("allergensStatus", request.allergensStatus().name());
        fields.put("category", request.category().name());
        fields.put("description", normalize(request.description()));
        fields.put("foodNotes", nullable(request.foodNotes()));
        fields.put("originalPriceMinor", request.originalPriceMinor() == null ? "" : request.originalPriceMinor().toString());
        fields.put("pickupEnd", instant(request.pickupEnd()));
        fields.put("pickupStart", instant(request.pickupStart()));
        fields.put("priceMinor", Integer.toString(request.priceMinor()));
        fields.put("quantityTotal", Integer.toString(request.quantityTotal()));
        fields.put("title", normalize(request.title()));
        StringBuilder canonical = new StringBuilder();
        fields.forEach((key, value) -> canonical.append(key.length()).append(':').append(key)
                .append(value.length()).append(':').append(value).append(';'));
        return digest(canonical.toString());
    }

    private static String digest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? "" : value.strip();
    }

    private static String instant(Instant value) {
        return value == null ? "" : value.toString();
    }
}
