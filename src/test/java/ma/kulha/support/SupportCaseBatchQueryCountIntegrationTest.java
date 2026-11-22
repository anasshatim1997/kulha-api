package ma.kulha.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.merchant.application.MerchantService;
import ma.kulha.merchant.domain.model.BusinessType;
import ma.kulha.merchant.domain.model.Merchant;
import ma.kulha.offer.application.OfferApplicationService;
import ma.kulha.offer.domain.model.AllergensStatus;
import ma.kulha.offer.domain.model.OfferCategory;
import ma.kulha.reservation.domain.model.Reservation;
import ma.kulha.reservation.domain.repository.ReservationRepository;
import ma.kulha.admin.support.application.SupportCaseService;
import net.ttddyy.dsproxy.QueryCountHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import(JdbcQueryCountTestConfiguration.class)
class SupportCaseBatchQueryCountIntegrationTest extends AbstractApiIntegrationTest {
    @Autowired MerchantService merchantService;
    @Autowired OfferApplicationService offerService;
    @Autowired SupportCaseService supportCases;
    @Autowired ReservationRepository reservationRepository;

    @Test
    void supportCaseListUsesConstantQueryCountForOneAndManyRows() {
        User admin = createUser("support-query-admin@example.com", true, Role.ADMIN);
        User support = createUser("support-query-agent@example.com", true, Role.SUPPORT);
        User customer = createUser("support-query-customer@example.com", true);
        User owner = createUser("support-query-owner@example.com", true);
        Merchant merchant = merchantService.apply(owner.id(), new Merchant.Profile(
                "Query Count Bakery", BusinessType.BAKERY, "12 rue Atlas", "Casablanca",
                "+212600000092", "Lun-Sam 8h-18h"));
        merchantService.approve(admin.id(), merchant.id());
        var offer = offerService.create(owner.id(), "Query Count Basket", "Description", OfferCategory.OTHER,
                500, null, 2, Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200),
                AllergensStatus.UNKNOWN, java.util.List.of(), null);
        offerService.publish(owner.id(), offer.id());
        Instant now = Instant.now();
        String reservationReference = "KLH-R-QCOUNT";
        reservationRepository.insert(Reservation.pending(UUID.randomUUID(), reservationReference, customer.id(),
                offer.id(), merchant.id(), "Query Count Basket", "OTHER", "Query Count Bakery",
                "12 rue Atlas", "Casablanca", null, now.plusSeconds(3600), now.plusSeconds(7200),
                "QCOUNT123", 1, 500, now));

        insertCases(customer.id(), support.id(), merchant.id(), offer.id(), reservationReference, 25);

        long oneRowQueries = countQueries(admin.id(), 1);
        long twentyFiveRowQueries = countQueries(admin.id(), 25);

        assertThat(oneRowQueries).isEqualTo(7);
        assertThat(twentyFiveRowQueries).isEqualTo(oneRowQueries);
    }

    private void insertCases(UUID customerId, UUID assigneeId, UUID merchantId, UUID offerId, String reservationReference, int count) {
        Instant now = Instant.now();
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO admin.support_cases
                        (id, reference, category, subject, description, priority, status, customer_user_id,
                         merchant_id, offer_id, assigned_to, created_at, updated_at)
                    VALUES (:id, :reference, 'ORDER_ISSUE', :subject, 'A support issue', 'MEDIUM', 'OPEN',
                            :customer, :merchant, :offer, :assignee, :now, :now)
                    """).param("id", id).param("reference", "KLH-Q-" + id.toString().substring(0, 8))
                    .param("subject", "Issue " + i).param("customer", customerId).param("merchant", merchantId)
                    .param("offer", offerId).param("reservation", reservationReference).param("assignee", assigneeId)
                    .param("now", now.atOffset(java.time.ZoneOffset.UTC)).update();
        }
    }

    private long countQueries(UUID adminId, int pageSize) {
        QueryCountHolder.clear();
        var page = supportCases.list(adminId, null, null, 0, pageSize);
        assertThat(page.items()).hasSize(pageSize);
        assertThat(page.total()).isEqualTo(25);
        var count = QueryCountHolder.get("kulha-module-query-count");
        assertThat(count).isNotNull();
        return count.getTotal();
    }
}
