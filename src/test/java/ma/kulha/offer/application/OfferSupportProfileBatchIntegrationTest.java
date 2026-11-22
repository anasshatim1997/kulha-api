package ma.kulha.offer.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.merchant.application.MerchantService;
import ma.kulha.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;

class OfferSupportProfileBatchIntegrationTest extends AbstractApiIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired MerchantService merchantService;

    @Test
    void twentyOfferSupportProfilesUseOneOfferOwnedQuery() throws SQLException {
        User owner = createUser("offer-support-batch-owner@example.com", true);
        User admin = createUser("offer-support-batch-admin@example.com", true, Role.ADMIN);
        UUID merchantId = merchantService.apply(owner.id(), new ma.kulha.merchant.domain.model.Merchant.Profile(
                "Offer Support Shop", ma.kulha.merchant.domain.model.BusinessType.BAKERY,
                "12 rue Atlas", "Casablanca", "+212600000099", "Lun-Sam 8h-18h")).id();
        merchantService.approve(admin.id(), merchantId);
        jdbc.sql("""
                INSERT INTO offer.offers (id, merchant_id, title, description, category, price_minor,
                    quantity_total, quantity_reserved, allergens_status, allergens, status, version, created_at, updated_at)
                SELECT gen_random_uuid(), :merchant, 'Support offer', 'Batch lookup', 'OTHER', 500,
                    2, 0, 'UNKNOWN', '{}', 'DRAFT', 0, now(), now()
                FROM generate_series(1, 20)
                """).param("merchant", merchantId).update();
        List<UUID> ids = jdbc.sql("SELECT id FROM offer.offers WHERE merchant_id=:merchant ORDER BY id")
                .param("merchant", merchantId).query(UUID.class).list();

        AtomicInteger statementCount = new AtomicInteger();
        DataSource counting = new DelegatingDataSource(dataSource) {
            @Override public Connection getConnection() throws SQLException {
                return countStatements(super.getConnection(), statementCount);
            }
        };
        OfferApi api = new OfferApiService(null, null, 30, Clock.systemUTC(), null, JdbcClient.create(counting));

        var profiles = api.findSupportProfiles(ids);

        assertThat(profiles).hasSize(20);
        assertThat(statementCount.get()).isEqualTo(1);
    }

    private static Connection countStatements(Connection connection, AtomicInteger count) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement") || method.getName().equals("prepareCall")) count.incrementAndGet();
                    try { return method.invoke(connection, args); }
                    catch (InvocationTargetException exception) { throw exception.getCause(); }
                });
    }
}
