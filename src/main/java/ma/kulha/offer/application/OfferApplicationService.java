package ma.kulha.offer.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ma.kulha.offer.domain.event.OfferEventEnvelope;
import ma.kulha.admin.application.AuditApi;
import ma.kulha.merchant.application.MerchantApi;
import ma.kulha.offer.domain.model.AllergensStatus;
import ma.kulha.offer.domain.model.Offer;
import ma.kulha.offer.domain.model.OfferCategory;
import ma.kulha.offer.domain.model.OfferStatus;
import ma.kulha.offer.domain.model.OfferSummary;
import ma.kulha.offer.domain.repository.OfferRepository;
import ma.kulha.offer.domain.repository.OfferIdempotencyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OfferApplicationService {
    private final OfferRepository offers;
    private final MerchantApi merchants;
    private final AuditApi audit;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final OfferIdempotencyRepository idempotency;

    OfferApplicationService(OfferRepository offers, MerchantApi merchants, AuditApi audit,
                            TransactionTemplate tx, Clock clock, ApplicationEventPublisher events,
                            OfferIdempotencyRepository idempotency) {
        this.offers = offers;
        this.merchants = merchants;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
        this.events = events;
        this.idempotency = idempotency;
    }

    public Offer create(UUID ownerId, String title, String description, OfferCategory category, int priceMinor,
                        Integer originalPriceMinor, int quantity, Instant pickupStart, Instant pickupEnd,
                        AllergensStatus allergensStatus, List<String> allergens, String foodNotes) {
        return create(ownerId, title, description, category, priceMinor, originalPriceMinor, quantity,
                pickupStart, pickupEnd, allergensStatus, allergens, foodNotes, null, null);
    }

    public Offer create(UUID ownerId, String title, String description, OfferCategory category, int priceMinor,
                        Integer originalPriceMinor, int quantity, Instant pickupStart, Instant pickupEnd,
                        AllergensStatus allergensStatus, List<String> allergens, String foodNotes,
                        String idempotencyKey, String requestHash) {
        UUID merchantId = requireMerchant(ownerId);
        return tx.execute(status -> {
            if (idempotencyKey != null) {
                if (requestHash == null || requestHash.length() != 64) throw new IllegalArgumentException("A SHA-256 request hash is required with Idempotency-Key");
                idempotency.lock(ownerId, idempotencyKey);
                var previous = idempotency.find(ownerId, idempotencyKey);
                if (previous.isPresent()) {
                    if (!previous.get().requestHash().equals(requestHash)) throw new IdempotencyConflictException();
                    return previous.get().responseSnapshot();
                }
            }
            merchants.lockForOfferMutation(merchantId);
            if (!merchants.isApproved(merchantId)) throw new MerchantNotApprovedException();
            if (offers.countActiveByMerchant(merchantId) >= 50) throw new OfferLimitExceededException();
            Offer offer = Offer.draft(merchantId, title, description, category, priceMinor, originalPriceMinor,
                    quantity, pickupStart, pickupEnd, allergensStatus, allergens, foodNotes, clock.instant());
            offers.insert(offer);
            Offer persisted = offers.findById(offer.id()).orElseThrow(OfferNotFoundException::new);
            if (idempotencyKey != null) idempotency.save(ownerId, idempotencyKey, requestHash, offer.id());
            return persisted;
        });
    }

    public OfferPage page(UUID ownerId, OfferStatus status, int page, int size) {
        UUID merchantId = requireMerchant(ownerId);
        List<OfferSummary> items = offers.listByMerchant(merchantId, status, size, page * size);
        long total = offers.countByMerchant(merchantId, status);
        return new OfferPage(items, total);
    }

    public Offer get(UUID ownerId, UUID offerId) {
        UUID merchantId = requireMerchant(ownerId);
        Offer offer = offers.findById(offerId).orElseThrow(OfferNotFoundException::new);
        if (!offer.merchantId().equals(merchantId)) throw new OfferNotFoundException();
        return offer;
    }

    public Offer edit(UUID ownerId, UUID offerId, long version, String title, String description,
                      OfferCategory category, int priceMinor, Integer originalPriceMinor, int quantity,
                      Instant pickupStart, Instant pickupEnd, AllergensStatus allergensStatus,
                      List<String> allergens, String foodNotes) {
        UUID merchantId = requireMerchant(ownerId);
        return tx.execute(status -> {
            Offer current = owned(merchantId, offerId);
            Offer updated = current.edit(title, description, category, priceMinor, originalPriceMinor,
                    quantity, pickupStart, pickupEnd, allergensStatus, allergens, foodNotes, version, clock.instant());
            if (!offers.update(updated, version)) throw new ma.kulha.offer.domain.model.StaleOfferVersionException();
            if (current.quantityTotal() != updated.quantityTotal()) {
                audit.append(ownerId, "OFFER_QUANTITY_CHANGED", "OFFER", offerId.toString(),
                        current.quantityTotal() + " -> " + updated.quantityTotal(), "SUCCESS");
            }
            boolean foodInfoChanged = !current.description().equals(updated.description())
                    || current.allergensStatus() != updated.allergensStatus()
                    || !current.allergens().equals(updated.allergens())
                    || !java.util.Objects.equals(current.foodNotes(), updated.foodNotes());
            if (foodInfoChanged) {
                audit.append(ownerId, "OFFER_FOOD_INFO_CHANGED", "OFFER", offerId.toString(), null, "SUCCESS");
            }
            return updated;
        });
    }

    public Offer publish(UUID ownerId, UUID offerId) {
        UUID merchantId = requireMerchant(ownerId);
        return tx.execute(status -> {
            merchants.lockForOfferMutation(merchantId);
            if (!merchants.isApproved(merchantId)) throw new MerchantNotApprovedException();
            Offer current = owned(merchantId, offerId);
            if (current.status() == OfferStatus.UNPUBLISHED && offers.countActiveByMerchant(merchantId) >= 50) {
                throw new OfferLimitExceededException();
            }
            Offer updated = current.publish(clock.instant());
            if (updated == current) return current;
            if (!offers.update(updated, current.version())) throw new ma.kulha.offer.domain.model.StaleOfferVersionException();
            audit.append(ownerId, "OFFER_PUBLISHED", "OFFER", offerId.toString(), null, "SUCCESS");
            emitAfterCommit("OfferPublished", updated);
            return updated;
        });
    }

    public Offer unpublish(UUID ownerId, UUID offerId) {
        UUID merchantId = requireMerchant(ownerId);
        return tx.execute(status -> {
            Offer current = owned(merchantId, offerId);
            Offer updated = current.unpublish(clock.instant());
            if (updated == current) return current;
            if (!offers.update(updated, current.version())) throw new ma.kulha.offer.domain.model.StaleOfferVersionException();
            audit.append(ownerId, "OFFER_UNPUBLISHED", "OFFER", offerId.toString(), null, "SUCCESS");
            emitAfterCommit("OfferUnpublished", updated);
            return updated;
        });
    }

    public Offer duplicate(UUID ownerId, UUID offerId) {
        return duplicate(ownerId, offerId, null, null);
    }

    public Offer duplicate(UUID ownerId, UUID offerId, String idempotencyKey, String requestHash) {
        UUID merchantId = requireMerchant(ownerId);
        return tx.execute(status -> {
            if (idempotencyKey != null) {
                if (requestHash == null || requestHash.length() != 64) throw new IllegalArgumentException("A SHA-256 request hash is required with Idempotency-Key");
                idempotency.lock(ownerId, idempotencyKey);
                var previous = idempotency.find(ownerId, idempotencyKey);
                if (previous.isPresent()) {
                    if (!previous.get().requestHash().equals(requestHash)) throw new IdempotencyConflictException();
                    return previous.get().responseSnapshot();
                }
            }
            merchants.lockForOfferMutation(merchantId);
            if (!merchants.isApproved(merchantId)) throw new MerchantNotApprovedException();
            Offer copy = owned(merchantId, offerId).duplicate(clock.instant());
            if (offers.countActiveByMerchant(merchantId) >= 50) throw new OfferLimitExceededException();
            offers.insert(copy);
            Offer persisted = offers.findById(copy.id()).orElseThrow(OfferNotFoundException::new);
            if (idempotencyKey != null) idempotency.save(ownerId, idempotencyKey, requestHash, copy.id());
            return persisted;
        });
    }

    public void cancel(UUID ownerId, UUID offerId) {
        UUID merchantId = requireMerchant(ownerId);
        tx.executeWithoutResult(status -> {
            Offer current = owned(merchantId, offerId);
            Offer updated = current.cancel(clock.instant());
            if (!offers.update(updated, current.version())) throw new ma.kulha.offer.domain.model.StaleOfferVersionException();
        });
    }

    public Offer changeQuantity(UUID ownerId, UUID offerId, int quantity, long version) {
        UUID merchantId = requireMerchant(ownerId);
        return tx.execute(status -> {
            Offer current = owned(merchantId, offerId);
            Offer updated = current.changeQuantity(quantity, version, clock.instant());
            if (!offers.update(updated, version)) throw new ma.kulha.offer.domain.model.StaleOfferVersionException();
            audit.append(ownerId, "OFFER_QUANTITY_CHANGED", "OFFER", offerId.toString(),
                    current.quantityTotal() + " -> " + quantity, "SUCCESS");
            return updated;
        });
    }

    public Offer adminSuspend(UUID adminId, UUID offerId, String reason) {
        return tx.execute(status -> {
            Offer current = offers.findById(offerId).orElseThrow(OfferNotFoundException::new);
            Offer updated = current.suspend(clock.instant());
            if (!offers.update(updated, current.version())) throw new ma.kulha.offer.domain.model.StaleOfferVersionException();
            audit.append(adminId, "OFFER_SUSPENDED", "OFFER", offerId.toString(), reason, "SUCCESS");
            emitAfterCommit("OfferSuspended", updated);
            return updated;
        });
    }

    public Offer adminReinstate(UUID adminId, UUID offerId, String reason) {
        return tx.execute(status -> {
            Offer current = offers.findById(offerId).orElseThrow(OfferNotFoundException::new);
            Offer updated = current.reinstate(clock.instant());
            if (!offers.update(updated, current.version())) throw new ma.kulha.offer.domain.model.StaleOfferVersionException();
            audit.append(adminId, "OFFER_REINSTATED", "OFFER", offerId.toString(), reason, "SUCCESS");
            return updated;
        });
    }

    public int expireDueOffers() {
        int changed = 0;
        Instant now = clock.instant();
        while (true) {
            Integer batchChanged = tx.execute(status -> {
                List<Offer> expired = offers.expireBatch(now, 500);
                for (Offer offer : expired) {
                    audit.append(SYSTEM_ACTOR, "OFFER_EXPIRED", "OFFER", offer.id().toString(),
                            "pickup window ended", "SUCCESS");
                    emitAfterCommit("OfferExpired", offer);
                }
                return expired.size();
            });
            int count = batchChanged == null ? 0 : batchChanged;
            changed += count;
            if (count < 500) return changed;
        }
    }

    private static final UUID SYSTEM_ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private void emitAfterCommit(String type, Offer offer) {
        OfferEventEnvelope event = new OfferEventEnvelope(UUID.randomUUID(), type, 1, clock.instant(), offer.id(),
                MDC.get("correlationId"), Map.of("offerId", offer.id(), "merchantId", offer.merchantId(),
                "status", offer.status().name(), "availableUnits", offer.availableUnits()));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { events.publishEvent(event); }
            });
        } else {
            events.publishEvent(event);
        }
    }

    private UUID requireApprovedMerchant(UUID ownerId) {
        UUID merchantId = requireMerchant(ownerId);
        if (!merchants.isApproved(merchantId)) throw new MerchantNotApprovedException();
        return merchantId;
    }

    private UUID requireMerchant(UUID ownerId) {
        return merchants.resolveActingMerchant(ownerId).orElseThrow(MerchantNotFoundException::new);
    }

    private Offer owned(UUID merchantId, UUID offerId) {
        Offer offer = offers.findById(offerId).orElseThrow(OfferNotFoundException::new);
        if (!offer.merchantId().equals(merchantId)) throw new OfferNotFoundException();
        return offer;
    }
}
