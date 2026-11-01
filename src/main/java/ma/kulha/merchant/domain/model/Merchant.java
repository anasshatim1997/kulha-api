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
        String phone,
        String openingHours,
        MerchantStatus status,
        String decisionReason,
        UUID decidedBy,
        Instant decidedAt,
        Instant createdAt,
        Instant updatedAt) {

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
    }

    /** The editable part of a merchant. Validates and trims its fields. */
    public record Profile(String name, BusinessType businessType, String address,
                          String city, String phone, String openingHours) {

        public Profile {
            Objects.requireNonNull(businessType, "businessType");
            name = required(name, "name", 120);
            address = required(address, "address", 255);
            city = required(city, "city", 80);
            phone = required(phone, "phone", 30);
            openingHours = required(openingHours, "openingHours", 500);
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
                profile.address(), profile.city(), profile.phone(), profile.openingHours(),
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
        boolean resubmitted = status == MerchantStatus.REJECTED;
        return new Merchant(id, ownerUserId, profile.name(), profile.businessType(),
                profile.address(), profile.city(), profile.phone(), profile.openingHours(),
                resubmitted ? MerchantStatus.PENDING : status,
                resubmitted ? null : decisionReason,
                resubmitted ? null : decidedBy,
                resubmitted ? null : decidedAt,
                createdAt, now);
    }

    private Merchant decided(MerchantStatus newStatus, String reason, UUID adminId, Instant now) {
        return new Merchant(id, ownerUserId, name, businessType, address, city, phone, openingHours,
                newStatus, reason, adminId, now, createdAt, now);
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
