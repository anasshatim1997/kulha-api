package ma.kulha.offer.infrastructure.search;

import java.util.UUID;
import ma.kulha.offer.domain.model.Offer;
import ma.kulha.offer.domain.repository.OfferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class OfferSearchRebuildListener {
    private static final Logger log = LoggerFactory.getLogger(OfferSearchRebuildListener.class);
    private static final int SCAN_BATCH_SIZE = 500;
    private final OfferRepository offers;
    private final OfferSearchIndexListener indexer;
    private final boolean enabled;

    OfferSearchRebuildListener(OfferRepository offers, OfferSearchIndexListener indexer,
            @Value("${app.search.elasticsearch.rebuild-on-startup:true}") boolean enabled) {
        this.offers = offers;
        this.indexer = indexer;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    void rebuildOfferIndex() {
        if (!enabled) return;
        UUID cursor = null;
        int indexed = 0;
        try {
            while (true) {
                var batch = offers.findAfter(cursor, SCAN_BATCH_SIZE);
                if (batch.isEmpty()) break;
                for (Offer offer : batch) {
                    indexer.defer(offer.id());
                    cursor = offer.id();
                    indexed++;
                }
                if (batch.size() < SCAN_BATCH_SIZE) break;
            }
            log.info("Queued {} offers for Elasticsearch startup reconciliation", indexed);
        } catch (RuntimeException exception) {
            log.error("Could not queue all offers for Elasticsearch startup reconciliation after {} offers", indexed, exception);
        }
    }
}
