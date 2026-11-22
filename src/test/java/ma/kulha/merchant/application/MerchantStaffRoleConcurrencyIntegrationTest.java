package ma.kulha.merchant.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import ma.kulha.identity.domain.model.User;
import ma.kulha.merchant.domain.model.BusinessType;
import ma.kulha.merchant.domain.model.Merchant;
import ma.kulha.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MerchantStaffRoleConcurrencyIntegrationTest extends AbstractApiIntegrationTest {

    @Autowired
    MerchantService merchantService;

    @Autowired
    MerchantStaffService staffService;

    @Test
    void concurrentMembershipMoveNeverLeavesGlobalStaffRoleOutOfSync() throws Exception {
        User admin = createUser("staff-race-admin@example.com", true, ma.kulha.identity.domain.model.Role.ADMIN);
        User ownerA = createUser("staff-race-owner-a@example.com", true);
        User ownerB = createUser("staff-race-owner-b@example.com", true);
        User staff = createUser("staff-race-member@example.com", true);

        Merchant merchantA = merchantService.apply(ownerA.id(), profile("Boulangerie Atlas"));
        Merchant merchantB = merchantService.apply(ownerB.id(), profile("Épicerie Medina"));
        merchantService.approve(admin.id(), merchantA.id());
        merchantService.approve(admin.id(), merchantB.id());
        staffService.addPickupStaff(ownerA.id(), staff.email(), true, true, false);

        for (int round = 0; round < 6; round++) {
            User removeOwner = round % 2 == 0 ? ownerA : ownerB;
            User addOwner = round % 2 == 0 ? ownerB : ownerA;
            runConcurrently(
                    () -> staffService.remove(removeOwner.id(), staff.id()),
                    () -> staffService.addPickupStaff(addOwner.id(), staff.email(), true, true, false));

            boolean hasActiveMembership = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM merchant.merchant_memberships
                                   WHERE user_id=:userId AND status='ACTIVE')
                    """).param("userId", staff.id()).query(Boolean.class).single();
            boolean hasGlobalRole = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM identity.user_roles
                                   WHERE user_id=:userId AND role='MERCHANT_STAFF')
                    """).param("userId", staff.id()).query(Boolean.class).single();

            assertThat(hasGlobalRole)
                    .as("global MERCHANT_STAFF role must match active memberships after concurrent round %s", round)
                    .isEqualTo(hasActiveMembership);
        }
    }

    private Merchant.Profile profile(String name) {
        return new Merchant.Profile(name, BusinessType.BAKERY, "12 rue des Fleurs", "Casablanca",
                "+212600000000", "Lun-Sam 7h-20h");
    }

    private void runConcurrently(ThrowingRunnable first, ThrowingRunnable second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> { start.await(); first.run(); return null; });
            var b = executor.submit(() -> { start.await(); second.run(); return null; });
            start.countDown();
            a.get(20, TimeUnit.SECONDS);
            b.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
