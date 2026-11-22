package ma.kulha.offer.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.merchant.application.MerchantService;
import ma.kulha.merchant.domain.model.BusinessType;
import ma.kulha.merchant.domain.model.Merchant;
import ma.kulha.offer.domain.model.AllergensStatus;
import ma.kulha.offer.domain.model.OfferCategory;
import ma.kulha.support.AbstractApiIntegrationTest;
import ma.kulha.support.JdbcQueryCountTestConfiguration;
import net.ttddyy.dsproxy.QueryCountHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import(JdbcQueryCountTestConfiguration.class)
class AdminOfferModerationBatchQueryCountIntegrationTest extends AbstractApiIntegrationTest {
    @Autowired MerchantService merchantService;
    @Autowired OfferApplicationService offerService;
    @Autowired AdminOfferModerationService moderation;

    @Test
    void offerModerationUsesBoundedBatchQueriesAsPageSizeGrows() {
        User owner = createUser("moderation-query-owner@example.com", true);
        User admin = createUser("moderation-query-admin@example.com", true, Role.ADMIN);
        Merchant merchant = merchantService.apply(owner.id(), new Merchant.Profile(
                "Moderation Query Bakery", BusinessType.BAKERY, "12 rue Atlas", "Casablanca",
                "+212600000097", "Lun-Sam 8h-18h"));
        merchantService.approve(admin.id(), merchant.id());

        createOffers(owner.id(), 2, 0);
        long twoOfferQueries = countOfferQueries(2);

        createOffers(owner.id(), 18, 2);
        long twentyOfferQueries = countOfferQueries(20);

        assertThat(twoOfferQueries).isEqualTo(4);
        assertThat(twentyOfferQueries).isEqualTo(twoOfferQueries);
    }

    private void createOffers(java.util.UUID ownerId, int count, int offset) {
        for (int i = 0; i < count; i++) {
            offerService.create(ownerId, "Moderation basket " + (offset + i), "Description",
                    OfferCategory.OTHER, 500, null, 2, Instant.now().plusSeconds(3600),
                    Instant.now().plusSeconds(7200), AllergensStatus.UNKNOWN, List.of(), null);
        }
    }

    private long countOfferQueries(int expectedOffers) {
        QueryCountHolder.clear();
        var page = moderation.list(null, 0, 100);
        assertThat(page.items()).hasSize(expectedOffers);
        var count = QueryCountHolder.get("kulha-module-query-count");
        assertThat(count).isNotNull();
        return count.getTotal();
    }
}
