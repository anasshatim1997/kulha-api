package ma.kulha.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class AdminSupportSqlBoundaryTest {
    private static final Pattern FOREIGN_SCHEMA_TABLE = Pattern.compile(
            "\\b(?:FROM|JOIN|UPDATE|INTO|REFERENCES|DELETE\\s+FROM)\\s+(identity|merchant|offer|reservation|notification|payment)\\.[a-z_]+\\b",
            Pattern.CASE_INSENSITIVE);

    @Test
    void supportSubdomainSqlMayOnlyAddressAdminOwnedTables() throws IOException {
        Path root = Path.of("src/main/java/ma/kulha/admin/support");
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> javaFiles = paths.filter(path -> path.toString().endsWith(".java")).toList();
            assertThat(javaFiles).isNotEmpty();
            for (Path file : javaFiles) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                assertThat(FOREIGN_SCHEMA_TABLE.matcher(source).find())
                        .as("cross-schema SQL reference in %s", file)
                        .isFalse();
            }
        }
    }
}
