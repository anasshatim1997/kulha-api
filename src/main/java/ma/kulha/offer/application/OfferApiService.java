package ma.kulha.offer.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ma.kulha.merchant.application.MerchantApi;
import ma.kulha.merchant.application.MerchantPublicProfile;
import ma.kulha.offer.domain.event.OfferEventEnvelope;
import ma.kulha.offer.domain.repository.OfferRepository;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
class OfferApiService implements OfferApi {
    private final OfferRepository offers;
    private final MerchantApi merchants;
    private final long reservationCutoffMinutes;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final JdbcClient jdbc;

    OfferApiService(OfferRepository offers, MerchantApi merchants,
            @Value("${app.offer.reservation-cutoff-minutes:30}") long reservationCutoffMinutes,
            Clock clock, ApplicationEventPublisher events, JdbcClient jdbc) {
        this.offers = offers;
        this.merchants = merchants;
        this.clock = clock;
        this.events = events;
        this.jdbc = jdbc;
        if (reservationCutoffMinutes < 0) throw new IllegalArgumentException("app.offer.reservation-cutoff-minutes cannot be negative");
        this.reservationCutoffMinutes = reservationCutoffMinutes;
    }

    @Override public boolean tryHoldUnits(UUID offerId, int units) {
        boolean held = offers.tryHoldUnits(offerId, units);
        if (held) publishSearchRefresh(offerId);
        return held;
    }
    @Override public void releaseUnits(UUID offerId, int units) {
        if (units < 1) throw new IllegalArgumentException("units must be positive");
        if (!offers.releaseUnits(offerId, units)) throw new IllegalStateException("Cannot release more units than are reserved.");
        publishSearchRefresh(offerId);
    }
    @Override public boolean isReservable(UUID offerId, Instant now) {
        return offers.findById(offerId).filter(o -> o.isReservable(now, reservationCutoffMinutes))
                .filter(o -> merchants.isApproved(o.merchantId())).isPresent();
    }
    @Override public Optional<OfferSnapshot> snapshot(UUID offerId) {
        return offers.findById(offerId).flatMap(offer ->
                merchants.findApprovedPublicProfiles(List.of(offer.merchantId())).stream().findFirst()
                .map((MerchantPublicProfile merchant) -> new OfferSnapshot(
                        offer.id(), offer.merchantId(), offer.priceMinor(), offer.originalPriceMinor(),
                        offer.pickupStart(), offer.pickupEnd(), offer.status() == ma.kulha.offer.domain.model.OfferStatus.PUBLISHED,
                        offer.availableUnits(),
                        offer.title(), offer.category().name(), merchant.name(), merchant.address(),
                        merchant.city(), merchant.pickupInstructions())));
    }

    @Override public List<SupportOfferProfile> findSupportProfiles(Collection<UUID> offerIds) {
        if (offerIds == null || offerIds.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT id, title, status
                FROM offer.offers
                WHERE id IN (:ids)
                """)
                .param("ids", offerIds)
                .query((rs, row) -> new SupportOfferProfile(rs.getObject("id", UUID.class),
                        rs.getString("title"), rs.getString("status")))
                .list();
    }

    private void publishSearchRefresh(UUID offerId) {
        OfferEventEnvelope event = new OfferEventEnvelope(UUID.randomUUID(), "OfferChanged", 1, clock.instant(),
                offerId, MDC.get("correlationId"), Map.of("offerId", offerId));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { events.publishEvent(event); }
            });
        } else events.publishEvent(event);
    }
}
