package ma.kulha.merchant.application;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.QueryCountHolder;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Wraps the test datasource so integration tests can assert actual JDBC execution counts. */
@TestConfiguration
class MerchantStaffQueryCountTestConfiguration {
    @Bean
    static BeanPostProcessor queryCountingDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!"dataSource".equals(beanName) || !(bean instanceof DataSource dataSource)
                        || bean instanceof QueryCountingDataSource) {
                    return bean;
                }
                QueryCountHolder.clear();
                DataSource proxy = ProxyDataSourceBuilder.create(dataSource)
                        .name("merchant-staff-query-count")
                        .countQuery()
                        .build();
                return new QueryCountingDataSource(proxy, dataSource);
            }
        };
    }

    private static final class QueryCountingDataSource implements DataSource, AutoCloseable {
        private final DataSource delegate;
        private final AutoCloseable original;

        private QueryCountingDataSource(DataSource delegate, DataSource original) {
            this.delegate = delegate;
            this.original = (AutoCloseable) original;
        }

        @Override public Connection getConnection() throws SQLException { return delegate.getConnection(); }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            return delegate.getConnection(username, password);
        }
        @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
        @Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
        @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
        @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
        @Override public Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
        @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }
        @Override public void close() throws Exception { original.close(); }
    }
}
