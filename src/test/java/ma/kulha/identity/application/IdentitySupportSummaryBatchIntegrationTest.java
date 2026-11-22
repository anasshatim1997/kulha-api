package ma.kulha.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import ma.kulha.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;

class IdentitySupportSummaryBatchIntegrationTest extends AbstractApiIntegrationTest {
    @Autowired DataSource dataSource;

    @Test
    void twentyFiveSupportIdentitySummariesUseOneQueryAndDoNotLoadPii() throws SQLException {
        List<UUID> ids = java.util.stream.IntStream.range(0, 25)
                .mapToObj(i -> createUser("support-batch-" + i + "@example.com", true).id())
                .toList();
        AtomicInteger statementCount = new AtomicInteger();
        DataSource counting = new DelegatingDataSource(dataSource) {
            @Override public Connection getConnection() throws SQLException {
                return countStatements(super.getConnection(), statementCount);
            }
        };
        IdentityApi api = new IdentityApiService(users, JdbcClient.create(counting));

        var summaries = api.findSupportSummaries(ids);

        assertThat(summaries).hasSize(25);
        assertThat(summaries).allSatisfy(summary -> {
            assertThat(summary.fullName()).isEqualTo("Test User");
            assertThat(summary.active()).isTrue();
        });
        assertThat(statementCount.get()).isEqualTo(1);
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
