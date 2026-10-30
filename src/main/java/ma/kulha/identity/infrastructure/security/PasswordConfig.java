package ma.kulha.identity.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
class PasswordConfig {

    /**
     * Argon2id with a random salt per password.
     * Parameters: salt 16 bytes, hash 32 bytes, parallelism 1, memory 19456 KiB (19 MiB), 2 iterations.
     * Requires Bouncy Castle (bcprov) on the classpath.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    }
}
