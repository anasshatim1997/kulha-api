package ma.kulha.merchant.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A merchant (shop) and its approval status. Immutable: every change returns a new instance.
 * <p>
 * Status rules:
 * PENDING -> APPROVED (approve) or REJECTED (reject, with a reason)
 * APPROVED -> SUSPENDED (suspend, with a reason)
 * SUSPENDED -> APPROVED (approve again)
 * REJECTED -> PENDING when the owner corrects the profile and resubmits
 */
public record Merchant(
        UUID id,
        UUID ownerUserId,
        String name,
        BusinessType businessType,
        String address,
        String city,
        String district,
        Double latitude,
        Double longitude,
        String phone,
        String openingHours,
        String pickupInstructions,
        MerchantStatus status,
        String decisionReason,
        UUID decidedBy,
        Instant decidedAt,
        Instant createdAt,
        Instant updatedAt) {


    public Merchant(UUID id, UUID ownerUserId, String name, BusinessType businessType, String address,
                    String city, String phone, String openingHours, String pickupInstructions,
                    MerchantStatus status, String decisionReason, UUID decidedBy, Instant decidedAt,
                    Instant createdAt, Instant updatedAt) {
        this(id, ownerUserId, name, businessType, address, city, null, null, null, phone, openingHours,
                pickupInstructions, status, decisionReason, decidedBy, decidedAt, createdAt, updatedAt);
    }

    /** Compatibility constructor for records created before shop coordinates were introduced. */
    public Merchant(UUID id, UUID ownerUserId, String name, BusinessType businessType, String address,
                    String city, String district, String phone, String openingHours, String pickupInstructions,
                    MerchantStatus status, String decisionReason, UUID decidedBy, Instant decidedAt,
                    Instant createdAt, Instant updatedAt) {
        this(id, ownerUserId, name, businessType, address, city, district, null, null, phone, openingHours,
                pickupInstructions, status, decisionReason, decidedBy, decidedAt, createdAt, updatedAt);
    }

    public Merchant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(businessType, "businessType");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(city, "city");
        Objects.requireNonNull(phone, "phone");
        Objects.requireNonNull(openingHours, "openingHours");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        validateCoordinatePair(latitude, longitude);
    }

    private static void validateCoordinatePair(Double latitude, Double longitude) {
        if ((latitude == null) != (longitude == null)) {
            throw new IllegalArgumentException("latitude and longitude must be provided together");
        }
        if (latitude != null && (!Double.isFinite(latitude) || latitude < -90 || latitude > 90)) {
            throw new IllegalArgumentException("latitude must be between -90 and 90");
        }
        if (longitude != null && (!Double.isFinite(longitude) || longitude < -180 || longitude > 180)) {
            throw new IllegalArgumentException("longitude must be between -180 and 180");
        }
    }

    /** The editable part of a merchant. Validates and trims its fields. */
    public record Profile(String name, BusinessType businessType, String address,
                          String city, String district, String phone, String openingHours, String pickupInstructions,
                          Double latitude, Double longitude) {

        public Profile(String name, BusinessType businessType, String address, String city, String district,
                       String phone, String openingHours, String pickupInstructions) {
            this(name, businessType, address, city, district, phone, openingHours, pickupInstructions, null, null);
        }

        public Profile(String name, BusinessType businessType, String address, String city, String phone, String openingHours, String pickupInstructions) {
            this(name, businessType, address, city, null, phone, openingHours, pickupInstructions, null, null);
        }

        public Profile(String name, BusinessType businessType, String address, String city, String phone, String openingHours) {
            this(name, businessType, address, city, null, phone, openingHours, null, null, null);
        }

        public Profile {
            Objects.requireNonNull(businessType, "businessType");
            name = required(name, "name", 120);
            address = required(address, "address", 255);
            city = required(city, "city", 80);
            district = optional(district, "district", 80);
            phone = required(phone, "phone", 30);
            openingHours = required(openingHours, "openingHours", 500);
            pickupInstructions = optional(pickupInstructions, "pickupInstructions", 500);
            validateCoordinates(latitude, longitude);
        }

        private static void validateCoordinates(Double latitude, Double longitude) {
            if ((latitude == null) != (longitude == null)) {
                throw new IllegalArgumentException("latitude and longitude must be provided together");
            }
            if (latitude != null && (!Double.isFinite(latitude) || latitude < -90 || latitude > 90)) {
                throw new IllegalArgumentException("latitude must be between -90 and 90");
            }
            if (longitude != null && (!Double.isFinite(longitude) || longitude < -180 || longitude > 180)) {
                throw new IllegalArgumentException("longitude must be between -180 and 180");
            }
        }

        private static String optional(String value, String field, int maxLength) {
            if (value == null || value.isBlank()) return null;
            String trimmed = value.trim();
            if (trimmed.length() > maxLength) throw new IllegalArgumentException(field + " is too long");
            return trimmed;
        }

        private static String required(String value, String field, int maxLength) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            String trimmed = value.trim();
            if (trimmed.length() > maxLength) {
                throw new IllegalArgumentException(field + " is too long");
            }
            return trimmed;
        }
    }

    public static Merchant apply(UUID ownerUserId, Profile profile, Instant now) {
        return new Merchant(UUID.randomUUID(), ownerUserId, profile.name(), profile.businessType(),
                profile.address(), profile.city(), profile.district(), profile.latitude(), profile.longitude(),
                profile.phone(), profile.openingHours(), profile.pickupInstructions(),
                MerchantStatus.PENDING, null, null, null, now, now);
    }

    public boolean isApproved() {
        return status == MerchantStatus.APPROVED;
    }

    public Merchant approve(UUID adminId, Instant now) {
        if (status != MerchantStatus.PENDING && status != MerchantStatus.SUSPENDED) {
            throw new InvalidMerchantStateException("Only a pending or suspended merchant can be approved.");
        }
        return decided(MerchantStatus.APPROVED, null, adminId, now);
    }

    public Merchant reject(UUID adminId, String reason, Instant now) {
        if (status != MerchantStatus.PENDING) {
            throw new InvalidMerchantStateException("Only a pending merchant can be rejected.");
        }
        return decided(MerchantStatus.REJECTED, requireReason(reason), adminId, now);
    }

    public Merchant suspend(UUID adminId, String reason, Instant now) {
        if (status != MerchantStatus.APPROVED) {
            throw new InvalidMerchantStateException("Only an approved merchant can be suspended.");
        }
        return decided(MerchantStatus.SUSPENDED, requireReason(reason), adminId, now);
    }

    /** Changes the profile. A rejected merchant that corrects its profile goes back to PENDING. */
    public Merchant withProfile(Profile profile, Instant now) {
        if (status == MerchantStatus.SUSPENDED) {
            throw new InvalidMerchantStateException("A suspended merchant cannot edit its profile.");
        }
        boolean resubmitted = status == MerchantStatus.REJECTED || status == MerchantStatus.INFO_REQUIRED;
        Double nextLatitude = profile.latitude() == null ? latitude : profile.latitude();
        Double nextLongitude = profile.longitude() == null ? longitude : profile.longitude();
        return new Merchant(id, ownerUserId, profile.name(), profile.businessType(),
                profile.address(), profile.city(), profile.district(), nextLatitude, nextLongitude,
                profile.phone(), profile.openingHours(), profile.pickupInstructions(),
                resubmitted ? MerchantStatus.PENDING : status,
                resubmitted ? null : decisionReason,
                resubmitted ? null : decidedBy,
                resubmitted ? null : decidedAt,
                createdAt, now);
    }

    public Merchant requestInformation(UUID adminId, String reason, Instant now) {
        if (status != MerchantStatus.PENDING) throw new InvalidMerchantStateException("Only a pending merchant can be asked for more information.");
        return decided(MerchantStatus.INFO_REQUIRED, requireReason(reason), adminId, now);
    }

    private Merchant decided(MerchantStatus newStatus, String reason, UUID adminId, Instant now) {
        return new Merchant(id, ownerUserId, name, businessType, address, city, district, latitude, longitude,
                phone, openingHours, pickupInstructions, newStatus, reason, adminId, now, createdAt, now);
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > 500) {
            throw new IllegalArgumentException("The reason is too long");
        }
        return trimmed;
    }
}
