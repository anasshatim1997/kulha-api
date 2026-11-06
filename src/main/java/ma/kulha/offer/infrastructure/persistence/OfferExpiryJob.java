package ma.kulha.offer.infrastructure.persistence;

import ma.kulha.offer.application.OfferApplicationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class OfferExpiryJob {
    private final OfferApplicationService offers;
    OfferExpiryJob(OfferApplicationService offers) { this.offers = offers; }
    @Scheduled(fixedDelayString = "${app.offer.expiry-delay-ms:60000}")
    void expireOffers() { offers.expireDueOffers(); }
}
