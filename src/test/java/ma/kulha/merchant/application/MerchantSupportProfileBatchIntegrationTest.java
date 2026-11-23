package ma.kulha.merchant.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import ma.kulha.identity.domain.model.User;
import ma.kulha.merchant.domain.model.BusinessType;
import ma.kulha.merchant.domain.model.Merchant;
import ma.kulha.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;

class MerchantSupportProfileBatchIntegrationTest extends AbstractApiIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired MerchantService merchantService;

    @Test
    void twentyMerchantSupportProfilesUseOneMerchantOwnedQuery() throws SQLException {
        List<UUID> merchantIds = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            User owner = createUser("merchant-support-batch-" + i + "@example.com", true);
            Merchant merchant = merchantService.apply(owner.id(), new Merchant.Profile(
                    "Support Shop " + i, BusinessType.BAKERY, "12 rue Atlas", "Casablanca",
                    "+21260000" + String.format("%04d", i), "Lun-Sam 8h-18h"));
            merchantIds.add(merchant.id());
        }
        AtomicInteger statementCount = new AtomicInteger();
        DataSource counting = new DelegatingDataSource(dataSource) {
            @Override public Connection getConnection() throws SQLException {
                return countStatements(super.getConnection(), statementCount);
            }
        };
        MerchantApi api = new MerchantApiService(null, JdbcClient.create(counting));

        var profiles = api.findSupportProfiles(merchantIds);

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
