package ma.kulha.offer.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import ma.kulha.admin.application.AdminSupportCaseReportingApi;
import ma.kulha.merchant.application.MerchantApi;
import ma.kulha.merchant.application.MerchantApi.SupportMerchantProfile;
import ma.kulha.offer.domain.model.Offer;
import ma.kulha.offer.domain.model.OfferStatus;
import ma.kulha.offer.domain.repository.OfferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminOfferModerationService {
    private final OfferRepository offers;
    private final MerchantApi merchants;
    private final AdminSupportCaseReportingApi supportCases;

    AdminOfferModerationService(OfferRepository offers, MerchantApi merchants,
                                AdminSupportCaseReportingApi supportCases) {
        this.offers = offers;
        this.merchants = merchants;
        this.supportCases = supportCases;
    }

    @Transactional(readOnly = true)
    public OfferPage list(String status, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.clamp(size, 1, 100);
        OfferStatus filter = status == null || status.isBlank()
                ? null : OfferStatus.valueOf(status.toUpperCase(java.util.Locale.ROOT));
        List<Offer> pageOffers = offers.listForAdminModeration(filter, safeSize, safePage * safeSize);
        List<OfferRow> rows = project(pageOffers);
        return new OfferPage(rows, safePage, safeSize, offers.countForAdminModeration(filter));
    }

    @Transactional(readOnly = true)
    public OfferRow get(UUID id) {
        Offer offer = offers.findById(id).orElseThrow(OfferNotFoundException::new);
        return project(List.of(offer)).getFirst();
    }

    private List<OfferRow> project(List<Offer> pageOffers) {
        if (pageOffers.isEmpty()) return List.of();
        var merchantIds = pageOffers.stream().map(Offer::merchantId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        var offerIds = pageOffers.stream().map(Offer::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, SupportMerchantProfile> merchantProfiles = merchants.findSupportProfiles(merchantIds).stream()
                .collect(Collectors.toMap(SupportMerchantProfile::id, profile -> profile));
        Map<UUID, Long> reportCounts = supportCases.openCaseCountsByOfferIds(offerIds);
        return pageOffers.stream().map(offer -> {
            SupportMerchantProfile merchant = merchantProfiles.get(offer.merchantId());
            return new OfferRow(offer.id(), offer.title(),
                    merchant == null ? "Shop unavailable" : merchant.name(),
                    merchant == null ? null : merchant.city(), offer.priceMinor(), offer.originalPriceMinor(),
                    offer.quantityTotal(), offer.quantityReserved(), offer.allergensStatus().name(),
                    String.join(",", offer.allergens()), offer.foodNotes(), offer.status().name(),
                    reportCounts.getOrDefault(offer.id(), 0L), offer.pickupStart(), offer.pickupEnd(), offer.imageUrl());
        }).toList();
    }

    public record OfferRow(UUID id, String title, String shopName, String city, int priceMinor,
                           Integer originalPriceMinor, int quantityTotal, int quantityReserved,
                           String allergensStatus, String allergens, String foodNotes, String status, long reportedCount,
                           java.time.Instant pickupStart, java.time.Instant pickupEnd, String imageUrl) {}
    public record OfferPage(List<OfferRow> items, int page, int size, long total) {}
}
