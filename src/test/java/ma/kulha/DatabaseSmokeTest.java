package ma.kulha;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class DatabaseSmokeTest {

    @Autowired
    DataSource dataSource;

    @Test
    void migrationsCreatedAllModuleSchemas() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rs = statement.executeQuery(
                     "select count(*) from information_schema.schemata where schema_name in "
                             + "('identity','merchant','offer','reservation','notification','payment','admin')")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(7);
        }
    }
}