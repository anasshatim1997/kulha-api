package ma.kulha.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordConfigTest {

    private final PasswordEncoder encoder = new PasswordConfig().passwordEncoder();

    @Test
    void encodedPasswordMatchesAndIsNotPlaintext() {
        String hash = encoder.encode("correct horse battery");

        assertThat(hash).isNotEqualTo("correct horse battery").startsWith("$argon2id$");
        assertThat(encoder.matches("correct horse battery", hash)).isTrue();
        assertThat(encoder.matches("wrong password", hash)).isFalse();
    }

    @Test
    void samePasswordGivesDifferentHashes() {
        String first = encoder.encode("correct horse battery");
        String second = encoder.encode("correct horse battery");

        assertThat(first).isNotEqualTo(second);
    }
}
