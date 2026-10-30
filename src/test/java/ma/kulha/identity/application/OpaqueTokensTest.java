package ma.kulha.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OpaqueTokensTest {

    @Test
    void generatedTokensAreLongAndDifferent() {
        String first = OpaqueTokens.generate();
        String second = OpaqueTokens.generate();

        assertThat(first).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void hashIsDeterministicLowerCaseHexOf64Characters() {
        String hash = OpaqueTokens.hash("some-token");

        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
        assertThat(OpaqueTokens.hash("some-token")).isEqualTo(hash);
    }

    @Test
    void hashDiffersFromTheTokenAndFromOtherTokens() {
        assertThat(OpaqueTokens.hash("token-a")).isNotEqualTo("token-a");
        assertThat(OpaqueTokens.hash("token-a")).isNotEqualTo(OpaqueTokens.hash("token-b"));
    }
}
