package ma.kulha.merchant.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.merchant.application.MerchantOperationsService;
import ma.kulha.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MerchantSalesExportIntegrationTest extends AbstractApiIntegrationTest {

    @Autowired
    MerchantOperationsService operations;

    @Test
    void salesAndCsvExportUseCompletionDateAndOnlyCompletedReservations() throws Exception {
        String merchantId = applyAs("sales-owner@example.com", "Boulangerie Atlas");
        User owner = users.findByEmail("sales-owner@example.com").orElseThrow();
        User admin = createUser("sales-admin@example.com", true, Role.ADMIN);
        User customer = createUser("sales-customer@example.com", true);
        assertThat(doPostEmpty("/api/v1/admin/merchants/" + merchantId + "/approve", tokenFor(admin)).getStatus())
                .isEqualTo(200);

        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 2);

        insertReservation(UUID.fromString(merchantId), customer.id(), "KLH-SALES-INCLUDED",
                "=HYPERLINK(\"https://evil.example\",\"click\")", "COMPLETED",
                "2026-10-31T12:00:00Z", "2026-11-02T12:00:00Z");
        insertReservation(UUID.fromString(merchantId), customer.id(), "KLH-SALES-OUTSIDE",
                "Created in range, completed outside", "COMPLETED",
                "2026-11-01T12:00:00Z", "2026-11-03T12:00:00Z");
        insertReservation(UUID.fromString(merchantId), customer.id(), "KLH-SALES-CANCELLED",
                "Cancelled reservation", "CANCELLED",
                "2026-11-01T13:00:00Z", "2026-11-02T13:00:00Z");

        MerchantOperationsService.Sales sales = operations.sales(owner.id(), from, to);
        String csv = operations.exportSales(owner.id(), from, to);

        assertThat(sales.basketsCollected()).isEqualTo(2);
        assertThat(sales.salesMinor()).isEqualTo(3000);
        assertThat(sales.cancelled()).isEqualTo(1);
        assertThat(sales.notCollected()).isZero();

        assertThat(csv.split("\\R")).hasSize(2);
        assertThat(csv).contains("2026-11-02")
                .contains("KLH-SALES-INCLUDED")
                .contains("\"'=HYPERLINK(")
                .doesNotContain("KLH-SALES-OUTSIDE")
                .doesNotContain("KLH-SALES-CANCELLED");
    }

    @Test
    void exportPaginatesMoreThanOneThousandCompletedReservationsWithoutDuplicates() throws Exception {
        String merchantId = applyAs("sales-batch-owner@example.com", "Boulangerie Batch");
        User owner = users.findByEmail("sales-batch-owner@example.com").orElseThrow();
        User admin = createUser("sales-batch-admin@example.com", true, Role.ADMIN);
        User customer = createUser("sales-batch-customer@example.com", true);
        assertThat(doPostEmpty("/api/v1/admin/merchants/" + merchantId + "/approve", tokenFor(admin)).getStatus())
                .isEqualTo(200);

        jdbc.sql("""
                INSERT INTO reservation.reservations
                    (id, reference, customer_id, offer_id, merchant_id, offer_title, offer_category,
                     merchant_name, merchant_address, merchant_city, pickup_start, pickup_end, pickup_code,
                     quantity, unit_price_minor, total_price_minor, status, created_at, updated_at)
                SELECT gen_random_uuid(), 'KLH-BATCH-' || n, :customer, gen_random_uuid(), :merchant,
                       'Panier du fournil', 'BAKERY', 'Boulangerie Batch', '12 rue des Fleurs', 'Casablanca',
                       '2026-11-04T12:00:00Z', '2026-11-04T13:00:00Z', '123456',
                       1, 1500, 1500, 'COMPLETED', '2026-11-01T12:00:00Z', '2026-11-02T12:00:00Z'
                FROM generate_series(1, 1001) AS series(n)
                """)
                .param("customer", customer.id())
                .param("merchant", UUID.fromString(merchantId))
                .update();

        String csv = operations.exportSales(owner.id(), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2));

        assertThat(csv.split("\\\\R")).hasSize(1002);
        assertThat(csv).contains("KLH-BATCH-1").contains("KLH-BATCH-1001");
    }

    private void insertReservation(UUID merchantId, UUID customerId, String reference,
                                   String offerTitle, String status, String createdAt, String updatedAt) {
        jdbc.sql("""
                INSERT INTO reservation.reservations
                    (id, reference, customer_id, offer_id, merchant_id, offer_title, offer_category,
                     merchant_name, merchant_address, merchant_city, pickup_start, pickup_end, pickup_code,
                     quantity, unit_price_minor, total_price_minor, status, created_at, updated_at)
                VALUES
                    (:id, :reference, :customer, :offer, :merchant, :title, 'BAKERY',
                     'Boulangerie Atlas', '12 rue des Fleurs', 'Casablanca',
                     '2026-11-04T12:00:00Z', '2026-11-04T13:00:00Z', '123456',
                     2, 1500, 3000, :status, :created, :updated)
                """)
                .param("id", UUID.randomUUID())
                .param("reference", reference)
                .param("customer", customerId)
                .param("offer", UUID.randomUUID())
                .param("merchant", merchantId)
                .param("title", offerTitle)
                .param("status", status)
                .param("created", OffsetDateTime.parse(createdAt))
                .param("updated", OffsetDateTime.parse(updatedAt))
                .update();
    }
}
