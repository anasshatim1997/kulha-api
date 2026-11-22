package ma.kulha.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ModuleSqlBoundaryTest {
    private static final List<String> MODULES = List.of(
            "identity", "merchant", "offer", "reservation", "notification", "payment", "admin");
    private static final Pattern SQL_TABLE = Pattern.compile(
            "\\b(?:FROM|JOIN|UPDATE|INTO|REFERENCES|DELETE\\s+FROM)\\s+(identity|merchant|offer|reservation|notification|payment|admin)\\.[a-z_]+\\b",
            Pattern.CASE_INSENSITIVE);

    @Test
    void moduleSqlMayOnlyReferenceItsOwnSchema() throws IOException {
        for (String module : MODULES) {
            Path root = Path.of("src/main/java/ma/kulha", module);
            try (Stream<Path> paths = Files.walk(root)) {
                List<Path> javaFiles = paths.filter(path -> path.toString().endsWith(".java")).toList();
                for (Path file : javaFiles) {
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    Matcher matcher = SQL_TABLE.matcher(source);
                    while (matcher.find()) {
                        assertThat(matcher.group(1).toLowerCase(java.util.Locale.ROOT))
                                .as("SQL table reference %s in module %s (%s)", matcher.group(), module, file)
                                .isEqualTo(module);
                    }
                }
            }
        }
    }
}
