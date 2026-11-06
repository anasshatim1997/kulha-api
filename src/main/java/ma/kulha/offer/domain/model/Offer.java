package ma.kulha.offer.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record Offer(
        UUID id,
        UUID merchantId,
        String title,
        String description,
        OfferCategory category,
        int priceMinor,
        Integer originalPriceMinor,
        int quantityTotal,
        int quantityReserved,
        Instant reservationStartedAt,
        Instant pickupStart,
        Instant pickupEnd,
        AllergensStatus allergensStatus,
        List<String> allergens,
        String foodNotes,
        OfferStatus status,
        Instant publishedAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public Offer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(merchantId, "merchantId");
        title = required(title, "title", 120);
        description = required(description, "description", 1000);
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(allergensStatus, "allergensStatus");
        allergens = allergens == null ? List.of() : List.copyOf(allergens);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (priceMinor < 100 || priceMinor > 200000) throw new InvalidOfferDataException("priceMinor must be 100..200000");
        if (originalPriceMinor != null && originalPriceMinor < priceMinor) throw new InvalidOfferDataException("originalPriceMinor must be at least priceMinor");
        if (quantityTotal < 1 || quantityTotal > 500) throw new InvalidOfferDataException("quantityTotal must be 1..500");
        if (quantityReserved < 0 || quantityReserved > quantityTotal) throw new InvalidOfferDataException("quantityReserved must be within quantityTotal");
        if (quantityReserved > 0 && reservationStartedAt == null) throw new InvalidOfferDataException("reservationStartedAt is required when units are reserved");
        if (allergensStatus == AllergensStatus.UNKNOWN && !allergens.isEmpty()) throw new InvalidOfferDataException("UNKNOWN allergens must be empty");
        for (String allergen : allergens) {
            if (!List.of("GLUTEN", "CRUSTACEANS", "EGGS", "FISH", "PEANUTS", "SOY", "MILK", "NUTS", "CELERY", "MUSTARD", "SESAME", "SULPHITES", "LUPIN", "MOLLUSCS").contains(allergen)) {
                throw new InvalidOfferDataException("Unsupported allergen: " + allergen);
            }
        }
        if ((pickupStart == null) != (pickupEnd == null)) throw new InvalidOfferDataException("Pickup start and end must both be set or both be absent");
        if (pickupStart != null && !pickupEnd.isAfter(pickupStart)) throw new InvalidOfferDataException("pickupEnd must be after pickupStart");
    }

    public Offer(UUID id, UUID merchantId, String title, String description, OfferCategory category,
                 int priceMinor, Integer originalPriceMinor, int quantityTotal, int quantityReserved,
                 Instant pickupStart, Instant pickupEnd, AllergensStatus allergensStatus, List<String> allergens,
                 String foodNotes, OfferStatus status, Instant publishedAt, long version,
                 Instant createdAt, Instant updatedAt) {
        this(id, merchantId, title, description, category, priceMinor, originalPriceMinor, quantityTotal,
                quantityReserved, quantityReserved > 0 ? updatedAt : null, pickupStart, pickupEnd,
                allergensStatus, allergens, foodNotes, status, publishedAt, version, createdAt, updatedAt);
    }

    public static Offer draft(UUID merchantId, String title, String description, OfferCategory category,
                              int priceMinor, Integer originalPriceMinor, int quantityTotal,
                              Instant pickupStart, Instant pickupEnd, AllergensStatus allergensStatus,
                              List<String> allergens, String foodNotes, Instant now) {
        return new Offer(UUID.randomUUID(), merchantId, title, description, category, priceMinor,
                originalPriceMinor, quantityTotal, 0, null, pickupStart, pickupEnd, allergensStatus,
                allergens, foodNotes, OfferStatus.DRAFT, null, 0, now, now);
    }

    public Offer edit(String newTitle, String newDescription, OfferCategory newCategory, int newPriceMinor,
                      Integer newOriginalPriceMinor, int newQuantityTotal, Instant newPickupStart,
                      Instant newPickupEnd, AllergensStatus newAllergensStatus, List<String> newAllergens,
                      String newFoodNotes, long expectedVersion, Instant now) {
        if (version != expectedVersion) throw new StaleOfferVersionException();
        if (status == OfferStatus.SUSPENDED || status == OfferStatus.EXPIRED || status == OfferStatus.CANCELLED) {
            throw new InvalidOfferStateException("This offer cannot be edited in its current state.");
        }
        if (reservationStartedAt != null && (priceMinor != newPriceMinor
                || !Objects.equals(originalPriceMinor, newOriginalPriceMinor)
                || !Objects.equals(pickupStart, newPickupStart)
                || !Objects.equals(pickupEnd, newPickupEnd))) {
            throw new LockedOfferFieldsException();
        }
        if (newQuantityTotal < quantityReserved) throw new InvalidOfferDataException("quantityTotal cannot be below reserved units");
        return new Offer(id, merchantId, newTitle, newDescription, newCategory, newPriceMinor,
                newOriginalPriceMinor, newQuantityTotal, quantityReserved, reservationStartedAt, newPickupStart, newPickupEnd,
                newAllergensStatus, newAllergens, newFoodNotes, status, publishedAt,
                version + 1, createdAt, now);
    }

    public Offer publish(Instant now) {
        if (status == OfferStatus.PUBLISHED) return this;
        if (status != OfferStatus.DRAFT && status != OfferStatus.UNPUBLISHED) throw new InvalidOfferStateException("Only a draft or unpublished offer can be published.");
        java.util.Map<String, String> fieldErrors = new java.util.LinkedHashMap<>();
        if (pickupStart == null) fieldErrors.put("pickupStart", "Pickup start is required");
        else if (!pickupStart.isAfter(now)) fieldErrors.put("pickupStart", "Pickup start must be in the future");
        if (pickupEnd == null) fieldErrors.put("pickupEnd", "Pickup end is required");
        else if (pickupStart != null && !pickupEnd.isAfter(pickupStart)) fieldErrors.put("pickupEnd", "Pickup end must be after pickup start");
        if (pickupStart != null && pickupEnd != null && pickupEnd.isAfter(pickupStart)) {
            if (Duration.between(pickupStart, pickupEnd).compareTo(Duration.ofHours(24)) > 0) {
                fieldErrors.put("pickupEnd", "Pickup window cannot exceed 24 hours");
            }
            if (pickupEnd.isAfter(now.plus(Duration.ofDays(7)))) {
                fieldErrors.put("pickupEnd", "Pickup end must be within 7 days");
            }
        }
        if (!fieldErrors.isEmpty()) throw new InvalidOfferWindowException(fieldErrors);
        return new Offer(id, merchantId, title, description, category, priceMinor, originalPriceMinor,
                quantityTotal, quantityReserved, reservationStartedAt, pickupStart, pickupEnd, allergensStatus, allergens,
                foodNotes, OfferStatus.PUBLISHED, now, version + 1, createdAt, now);
    }

    public Offer unpublish(Instant now) {
        if (status == OfferStatus.UNPUBLISHED) return this;
        if (status != OfferStatus.PUBLISHED) throw new InvalidOfferStateException("Only a published offer can be unpublished.");
        return copy(OfferStatus.UNPUBLISHED, publishedAt, quantityTotal, quantityReserved, version + 1, now);
    }

    public Offer changeQuantity(int quantity, long expectedVersion, Instant now) {
        if (version != expectedVersion) throw new StaleOfferVersionException();
        if (quantity < quantityReserved || quantity > 500) throw new InvalidOfferDataException("Quantity must be between reserved units and 500");
        if (status == OfferStatus.CANCELLED || status == OfferStatus.EXPIRED || status == OfferStatus.SUSPENDED) throw new InvalidOfferStateException("Quantity cannot change in this state.");
        return copy(status, publishedAt, quantity, quantityReserved, version + 1, now);
    }

    public Offer duplicate(Instant now) {
        return new Offer(UUID.randomUUID(), merchantId, title, description, category, priceMinor,
                originalPriceMinor, quantityTotal, 0, null, null, null, allergensStatus, allergens,
                foodNotes, OfferStatus.DRAFT, null, 0, now, now);
    }

    public Offer cancel(Instant now) {
        if (status != OfferStatus.DRAFT) throw new InvalidOfferStateException("Only a draft offer can be cancelled.");
        return copy(OfferStatus.CANCELLED, publishedAt, quantityTotal, quantityReserved, version + 1, now);
    }

    public Offer suspend(Instant now) {
        if (status != OfferStatus.PUBLISHED && status != OfferStatus.UNPUBLISHED) throw new InvalidOfferStateException("Only published or unpublished offers can be suspended.");
        return copy(OfferStatus.SUSPENDED, publishedAt, quantityTotal, quantityReserved, version + 1, now);
    }

    public Offer reinstate(Instant now) {
        if (status != OfferStatus.SUSPENDED) throw new InvalidOfferStateException("Only suspended offers can be reinstated.");
        return copy(OfferStatus.UNPUBLISHED, publishedAt, quantityTotal, quantityReserved, version + 1, now);
    }

    public Offer expired(Instant now) {
        return copy(OfferStatus.EXPIRED, publishedAt, quantityTotal, quantityReserved, version + 1, now);
    }

    public int availableUnits() { return quantityTotal - quantityReserved; }

    public boolean isReservable(Instant now) {
        return isReservable(now, 30);
    }

    public boolean isReservable(Instant now, long cutoffMinutes) {
        if (cutoffMinutes < 0) throw new IllegalArgumentException("cutoffMinutes cannot be negative");
        return status == OfferStatus.PUBLISHED && pickupEnd != null
                && now.isBefore(pickupEnd.minus(Duration.ofMinutes(cutoffMinutes))) && availableUnits() > 0;
    }

    private Offer copy(OfferStatus nextStatus, Instant nextPublishedAt, int total, int reserved, long nextVersion, Instant now) {
        return new Offer(id, merchantId, title, description, category, priceMinor, originalPriceMinor,
                total, reserved, reservationStartedAt, pickupStart, pickupEnd, allergensStatus, allergens, foodNotes,
                nextStatus, nextPublishedAt, nextVersion, createdAt, now);
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank()) throw new InvalidOfferDataException(field + " is required");
        String trimmed = value.trim();
        if (trimmed.length() > max) throw new InvalidOfferDataException(field + " is too long");
        return trimmed;
    }
}
