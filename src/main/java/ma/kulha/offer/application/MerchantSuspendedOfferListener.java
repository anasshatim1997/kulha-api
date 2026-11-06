package ma.kulha.offer.application;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import ma.kulha.admin.application.AuditApi;
import ma.kulha.merchant.application.MerchantSuspended;
import ma.kulha.offer.domain.event.OfferEventEnvelope;
import ma.kulha.offer.domain.model.Offer;
import ma.kulha.offer.domain.repository.OfferRepository;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class MerchantSuspendedOfferListener {
    private static final UUID SYSTEM_ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final OfferRepository offers;
    private final AuditApi audit;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    MerchantSuspendedOfferListener(OfferRepository offers, AuditApi audit, TransactionTemplate tx,
                                   Clock clock, ApplicationEventPublisher events) {
        this.offers = offers;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
        this.events = events;
    }

    @EventListener
    void onMerchantSuspended(MerchantSuspended event) {
        for (Offer listed : offers.findPublishedByMerchant(event.merchantId())) {
            tx.executeWithoutResult(status -> offers.findByIdForUpdate(listed.id()).ifPresent(current -> {
                if (current.status() != ma.kulha.offer.domain.model.OfferStatus.PUBLISHED) return;
                Offer suspended = current.suspend(clock.instant());
                if (!offers.update(suspended, current.version())) {
                    throw new IllegalStateException("The offer changed while its merchant was being suspended.");
                }
                audit.append(SYSTEM_ACTOR, "OFFER_SUSPENDED", "OFFER", current.id().toString(),
                        "merchant suspended", "SUCCESS");
                publishAfterCommit(suspended);
            }));
        }
    }

    private void publishAfterCommit(Offer offer) {
        OfferEventEnvelope event = new OfferEventEnvelope(UUID.randomUUID(), "OfferSuspended", 1, clock.instant(),
                offer.id(), MDC.get("correlationId"), Map.of("offerId", offer.id(), "merchantId", offer.merchantId(),
                "status", offer.status().name(), "availableUnits", offer.availableUnits()));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { events.publishEvent(event); }
            });
        } else {
            events.publishEvent(event);
        }
    }
}
