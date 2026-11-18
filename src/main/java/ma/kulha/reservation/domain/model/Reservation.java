package ma.kulha.reservation.domain.model;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
/** Immutable reservation record. Offer and merchant values are snapshots taken at booking. */
public record Reservation(UUID id, String reference, UUID customerId, UUID offerId, UUID merchantId,
        String offerTitle, String offerCategory, String merchantName, String merchantAddress, String merchantCity,
        String merchantPickupInstructions, Instant pickupStart, Instant pickupEnd, String pickupCode,
        int quantity, int unitPriceMinor, int totalPriceMinor, ReservationStatus status,
        Instant createdAt, Instant updatedAt) {
    public Reservation {
        Objects.requireNonNull(id, "id"); reference = required(reference, "reference", 40);
        Objects.requireNonNull(customerId, "customerId"); Objects.requireNonNull(offerId, "offerId");
        Objects.requireNonNull(merchantId, "merchantId"); offerTitle = required(offerTitle, "offerTitle", 120);
        offerCategory = required(offerCategory, "offerCategory", 30);
        merchantName = required(merchantName, "merchantName", 120);
        merchantAddress = required(merchantAddress, "merchantAddress", 255);
        merchantCity = required(merchantCity, "merchantCity", 80);
        Objects.requireNonNull(pickupStart, "pickupStart"); Objects.requireNonNull(pickupEnd, "pickupEnd");
        if (!pickupEnd.isAfter(pickupStart)) throw new IllegalArgumentException("pickupEnd must be after pickupStart");
        pickupCode = required(pickupCode, "pickupCode", 12);
        if (quantity < 1 || quantity > 500 || unitPriceMinor < 1 || totalPriceMinor < 1
                || Math.multiplyExact(unitPriceMinor, quantity) != totalPriceMinor) {
            throw new IllegalArgumentException("reservation pricing or quantity is inconsistent");
        }
        Objects.requireNonNull(status, "status"); Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
    public static Reservation pending(UUID id, String reference, UUID customerId, UUID offerId, UUID merchantId,
            String offerTitle, String offerCategory, String merchantName, String merchantAddress, String merchantCity,
            String merchantPickupInstructions, Instant pickupStart, Instant pickupEnd, String pickupCode,
            int quantity, int unitPriceMinor, Instant now) {
        return new Reservation(id, reference, customerId, offerId, merchantId, offerTitle, offerCategory,
                merchantName, merchantAddress, merchantCity, merchantPickupInstructions, pickupStart, pickupEnd,
                pickupCode, quantity, unitPriceMinor, Math.multiplyExact(unitPriceMinor, quantity),
                ReservationStatus.PENDING, now, now);
    }
    public Reservation confirm(Instant now) {
        requireStatus(ReservationStatus.PENDING, "Only a pending reservation can be confirmed.");
        return withStatus(ReservationStatus.CONFIRMED, now);
    }
    public Reservation cancel(Instant now) {
        if (status != ReservationStatus.PENDING && status != ReservationStatus.CONFIRMED)
            throw new InvalidReservationStateException("Only a pending or confirmed reservation can be cancelled.");
        if (!now.isBefore(pickupStart))
            throw new InvalidReservationStateException("A reservation can only be cancelled before its pickup window starts.");
        return withStatus(ReservationStatus.CANCELLED, now);
    }
    public Reservation complete(Instant now) {
        requireStatus(ReservationStatus.CONFIRMED, "Only a confirmed reservation can be completed.");
        if (now.isBefore(pickupStart))
            throw new InvalidReservationStateException("A reservation cannot be completed before its pickup window.");
        return withStatus(ReservationStatus.COMPLETED, now);
    }
    public Reservation expire(Instant now) {
        if ((status != ReservationStatus.PENDING && status != ReservationStatus.CONFIRMED) || now.isBefore(pickupEnd))
            return this;
        return withStatus(ReservationStatus.EXPIRED, now);
    }
    public boolean canCancel(Instant now) {
        return (status == ReservationStatus.PENDING || status == ReservationStatus.CONFIRMED) && now.isBefore(pickupStart);
    }
    public ReservationGroup group(Instant now) {
        if (status == ReservationStatus.CANCELLED) return ReservationGroup.CANCELLED;
        if (status == ReservationStatus.COMPLETED || status == ReservationStatus.EXPIRED || !pickupEnd.isAfter(now))
            return ReservationGroup.PAST;
        return ReservationGroup.UPCOMING;
    }
    private void requireStatus(ReservationStatus expected, String message) {
        if (status != expected) throw new InvalidReservationStateException(message);
    }
    private Reservation withStatus(ReservationStatus next, Instant now) {
        return new Reservation(id, reference, customerId, offerId, merchantId, offerTitle, offerCategory,
                merchantName, merchantAddress, merchantCity, merchantPickupInstructions, pickupStart, pickupEnd,
                pickupCode, quantity, unitPriceMinor, totalPriceMinor, next, createdAt, now);
    }
    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        String clean = value.trim();
        if (clean.length() > max) throw new IllegalArgumentException(field + " is too long");
        return clean;
    }
}
