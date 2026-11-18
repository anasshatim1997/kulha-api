package ma.kulha.offer.infrastructure.search;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import ma.kulha.merchant.application.MerchantPublicApi;
import ma.kulha.merchant.application.MerchantPublicProfile;
import ma.kulha.merchant.application.MerchantProfileUpdated;
import ma.kulha.offer.domain.event.OfferEventEnvelope;
import ma.kulha.offer.domain.event.OfferExpiryBatchEvent;
import ma.kulha.offer.domain.model.Offer;
import ma.kulha.offer.domain.model.OfferStatus;
import ma.kulha.offer.domain.repository.OfferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class OfferSearchIndexListener {
    private static final Logger log = LoggerFactory.getLogger(OfferSearchIndexListener.class);
    // Larger bulk batches make startup reconciliation and expiry indexing converge much
    // faster without turning every offer into an individual Elasticsearch HTTP request.
    private static final int BATCH_SIZE = 500;
    private final OfferRepository offers;
    private final MerchantPublicApi merchants;
    private final ElasticsearchOfferSearch search;
    private final Clock clock;
    private final long cutoffMinutes;
    private final boolean syncEvents;
    private final ConcurrentHashMap<UUID, Long> pending = new ConcurrentHashMap<>();
    private volatile long nextRetryAt;
    private long retryDelayMillis = 1000;

    OfferSearchIndexListener(OfferRepository offers, MerchantPublicApi merchants, ElasticsearchOfferSearch search,
            Clock clock, @Value("${app.offer.reservation-cutoff-minutes:30}") long cutoffMinutes,
            @Value("${app.search.elasticsearch.sync-events:false}") boolean syncEvents) {
        this.offers = offers;
        this.merchants = merchants;
        this.search = search;
        this.clock = clock;
        if (cutoffMinutes < 0) throw new IllegalArgumentException("app.offer.reservation-cutoff-minutes cannot be negative");
        this.cutoffMinutes = cutoffMinutes;
        this.syncEvents = syncEvents;
    }

    @EventListener
    void onOfferChanged(OfferEventEnvelope event) {
        pending.merge(event.aggregateId(), 1L, Long::sum);
        if (syncEvents) processPending();
    }

    @EventListener
    void onOffersExpired(OfferExpiryBatchEvent event) {
        event.offerIds().forEach(id -> pending.merge(id, 1L, Long::sum));
        if (syncEvents) {
            while (!pending.isEmpty()) {
                int queuedBefore = pending.size();
                processPending();
                if (pending.size() >= queuedBefore) return;
            }
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onMerchantProfileUpdated(MerchantProfileUpdated event) {
        offers.findPublishedByMerchant(event.merchantId()).forEach(offer -> pending.merge(offer.id(), 1L, Long::sum));
        if (syncEvents) processPending();
    }

    @Scheduled(fixedDelayString = "${app.search.elasticsearch.retry-delay-ms:1000}",
            initialDelayString = "${app.search.elasticsearch.initial-delay-ms:1000}")
    void processPending() {
        long nowMillis = System.currentTimeMillis();
        if (nowMillis < nextRetryAt) return;
        Map<UUID, Long> pendingBatch = pending.entrySet().stream().limit(BATCH_SIZE)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (first, second) -> first,
                        java.util.LinkedHashMap::new));
        List<UUID> ids = List.copyOf(pendingBatch.keySet());
        if (ids.isEmpty()) return;
        try {
            Map<UUID, Offer> offerById = offers.findByIds(ids).stream()
                    .collect(Collectors.toMap(Offer::id, Function.identity()));
            Instant now = clock.instant();
            List<Offer> publicOffers = ids.stream().map(offerById::get).filter(java.util.Objects::nonNull)
                    .filter(offer -> offer.status() == OfferStatus.PUBLISHED && offer.pickupEnd() != null
                            && now.isBefore(offer.pickupEnd().minusSeconds(cutoffMinutes * 60))).toList();
            List<UUID> merchantIds = publicOffers.stream().map(Offer::merchantId).distinct().toList();
            Map<UUID, MerchantPublicProfile> profiles = merchantIds.isEmpty() ? Map.of()
                    : merchants.findApprovedPublicProfiles(merchantIds).stream()
                    .collect(Collectors.toMap(MerchantPublicProfile::merchantId, Function.identity()));
            List<OfferSearchDocument> upserts = new ArrayList<>();
            Set<UUID> upsertIds = new LinkedHashSet<>();
            for (Offer offer : publicOffers) {
                MerchantPublicProfile merchant = profiles.get(offer.merchantId());
                if (merchant == null) continue;
                upserts.add(new OfferSearchDocument(offer.id(), offer.merchantId(), offer.title(), offer.description(),
                        offer.category().name(), offer.priceMinor(), offer.originalPriceMinor(), offer.quantityTotal(),
                        offer.availableUnits(), offer.pickupStart(), offer.pickupEnd(), offer.publishedAt(), offer.createdAt(),
                        merchant.name(), merchant.city(), merchant.district(), merchant.address(), merchant.pickupInstructions(),
                        offer.allergens(), offer.allergensStatus().name(), offer.foodNotes(), merchant.latitude(), merchant.longitude()));
                upsertIds.add(offer.id());
            }
            List<UUID> deletes = ids.stream().filter(id -> !upsertIds.contains(id)).toList();
            search.applyBatch(upserts, deletes);
            pendingBatch.forEach((id, version) -> pending.remove(id, version));
            retryDelayMillis = 1000;
            nextRetryAt = 0;
        } catch (RuntimeException exception) {
            nextRetryAt = nowMillis + retryDelayMillis;
            retryDelayMillis = Math.min(retryDelayMillis * 2, 60000);
            log.warn("Elasticsearch offer batch failed; {} IDs remain queued for retry in {} ms",
                    ids.size(), nextRetryAt - nowMillis, exception);
        }
    }

    void defer(UUID offerId) {
        pending.merge(offerId, 1L, Long::sum);
    }
}
