package ma.kulha.support;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import net.ttddyy.dsproxy.QueryCountHolder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration
class JdbcQueryCountTestConfiguration {
    @Bean
    static BeanPostProcessor queryCountingDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!"dataSource".equals(beanName) || !(bean instanceof DataSource dataSource)
                        || bean instanceof CountingDataSource) return bean;
                DataSource proxy = ProxyDataSourceBuilder.create(dataSource)
                        .name("kulha-module-query-count").countQuery().build();
                return new CountingDataSource(proxy, (AutoCloseable) dataSource);
            }
        };
    }

    private record CountingDataSource(DataSource delegate, AutoCloseable original)
            implements DataSource, AutoCloseable {
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
