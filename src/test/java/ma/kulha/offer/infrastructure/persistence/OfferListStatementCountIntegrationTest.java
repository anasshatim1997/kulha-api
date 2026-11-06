package ma.kulha.offer.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import ma.kulha.offer.domain.model.OfferStatus;
import ma.kulha.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;

class OfferListStatementCountIntegrationTest extends AbstractApiIntegrationTest {

    @Autowired
    DataSource dataSource;

    @Test
    void twentyOfferListAndCountUseTwoOfferQueriesNotNPlusOneQueries() throws SQLException {
        UUID merchantId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO offer.offers (id, merchant_id, title, description, category, price_minor,
                    quantity_total, quantity_reserved, allergens_status, allergens, status, version, created_at, updated_at)
                SELECT gen_random_uuid(), :merchant, 'List offer', 'Summary projection item', 'OTHER', 500,
                    2, 0, 'UNKNOWN', '{}', 'DRAFT', 0, now(), now()
                FROM generate_series(1, 20)
                """).param("merchant", merchantId).update();

        AtomicInteger statementCount = new AtomicInteger();
        DataSource countingDataSource = new DelegatingDataSource(dataSource) {
            @Override
            public Connection getConnection() throws SQLException {
                return countStatements(super.getConnection(), statementCount);
            }
        };
        JdbcOfferRepository repository = new JdbcOfferRepository(JdbcClient.create(countingDataSource), 30);

        var items = repository.listByMerchant(merchantId, OfferStatus.DRAFT, 20, 0);
        long total = repository.countByMerchant(merchantId, OfferStatus.DRAFT);

        assertThat(items).hasSize(20);
        assertThat(total).isEqualTo(20);
        assertThat(statementCount.get()).isEqualTo(2);
    }

    private static Connection countStatements(Connection connection, AtomicInteger count) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement") || method.getName().equals("prepareCall")) count.incrementAndGet();
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }
}
