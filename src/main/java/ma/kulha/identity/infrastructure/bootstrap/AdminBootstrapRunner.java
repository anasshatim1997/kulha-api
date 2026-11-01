package ma.kulha.identity.infrastructure.bootstrap;

import ma.kulha.identity.application.AdminBootstrapService;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * At startup, creates the first administrator from ADMIN_BOOTSTRAP_EMAIL and ADMIN_BOOTSTRAP_PASSWORD
 * if no administrator exists yet. Remove the password from the environment afterwards.
 */
@Component
class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final AdminBootstrapService service;
    private final String email;
    private final String password;

    AdminBootstrapRunner(AdminBootstrapService service,
                         @Value("${ADMIN_BOOTSTRAP_EMAIL:}") String email,
                         @Value("${ADMIN_BOOTSTRAP_PASSWORD:}") String password) {
        this.service = service;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(@NonNull ApplicationArguments args) {
        AdminBootstrapService.Result result = service.ensureAdmin(email, password);
        switch (result) {
            case CREATED, PROMOTED -> log.info("Admin bootstrap: {}. Remove ADMIN_BOOTSTRAP_PASSWORD from the environment.", result);
            case INVALID_EMAIL, WEAK_PASSWORD -> log.warn("Admin bootstrap rejected: {}", result);
            default -> log.debug("Admin bootstrap: {}", result);
        }
        // The password is never logged.
    }
}
