package ma.kulha.merchant.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.merchant.domain.model.BusinessType;
import ma.kulha.merchant.domain.model.Merchant;
import ma.kulha.reservation.domain.model.Reservation;
import ma.kulha.reservation.domain.repository.ReservationRepository;
import ma.kulha.support.AbstractApiIntegrationTest;
import ma.kulha.support.JdbcQueryCountTestConfiguration;
import net.ttddyy.dsproxy.QueryCountHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import(JdbcQueryCountTestConfiguration.class)
class MerchantOrdersBatchQueryCountIntegrationTest extends AbstractApiIntegrationTest {
    @Autowired MerchantService merchantService;
    @Autowired MerchantOperationsService operations;
    @Autowired ReservationRepository reservations;

    @Test
    void merchantOrdersUseOneBatchedIdentityLookupAsOrderCountGrows() {
        User owner = createUser("orders-query-owner@example.com", true);
        User admin = createUser("orders-query-admin@example.com", true, Role.ADMIN);
        Merchant merchant = merchantService.apply(owner.id(), new Merchant.Profile(
                "Orders Query Bakery", BusinessType.BAKERY, "12 rue Atlas", "Casablanca",
                "+212600000096", "Lun-Sam 8h-18h"));
        merchantService.approve(admin.id(), merchant.id());

        insertOrders(merchant, 2, 0);
        long twoOrderQueries = countOrderQueries(owner.id(), 2);

        insertOrders(merchant, 18, 2);
        long twentyOrderQueries = countOrderQueries(owner.id(), 20);

        assertThat(twoOrderQueries).isEqualTo(5);
        assertThat(twentyOrderQueries).isEqualTo(twoOrderQueries);
    }

    private void insertOrders(Merchant merchant, int count, int offset) {
        Instant now = Instant.now();
        for (int i = 0; i < count; i++) {
            User customer = createUser("orders-query-customer-" + (offset + i) + "@example.com", true);
            String reference = "KLH-MQ-" + UUID.randomUUID().toString().substring(0, 8);
            reservations.insert(Reservation.pending(UUID.randomUUID(), reference, customer.id(), UUID.randomUUID(),
                    merchant.id(), "Snapshot basket", "OTHER", merchant.name(), merchant.address(), merchant.city(),
                    null, now.plusSeconds(3600), now.plusSeconds(7200), "MQ" + String.format("%010d", offset + i),
                    1, 500, now));
        }
    }

    private long countOrderQueries(UUID ownerId, int expectedOrders) {
        QueryCountHolder.clear();
        var rows = operations.orders(ownerId, "ALL");
        assertThat(rows).hasSize(expectedOrders);
        var count = QueryCountHolder.get("kulha-module-query-count");
        assertThat(count).isNotNull();
        return count.getTotal();
    }
}
