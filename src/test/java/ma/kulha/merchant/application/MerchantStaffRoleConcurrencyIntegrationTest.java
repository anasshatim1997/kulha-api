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
import org.springframework.web.server.ResponseStatusException;
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

        Merchant merchantA = merchantService.apply(ownerA.id(), profile("Boulangerie Atlas", "+212600000001"));
        Merchant merchantB = merchantService.apply(ownerB.id(), profile("Épicerie Medina", "+212600000002"));
        merchantService.approve(admin.id(), merchantA.id());
        merchantService.approve(admin.id(), merchantB.id());
        staffService.addPickupStaff(ownerA.id(), staff.email(), true, true, false);

        for (int round = 0; round < 6; round++) {
            User removeOwner = round % 2 == 0 ? ownerA : ownerB;
            User addOwner = round % 2 == 0 ? ownerB : ownerA;
            boolean addSucceeded = runConcurrently(
                    () -> staffService.remove(removeOwner.id(), staff.id()),
                    () -> {
                        try {
                            staffService.addPickupStaff(addOwner.id(), staff.email(), true, true, false);
                            return true;
                        } catch (ResponseStatusException conflict) {
                            if (conflict.getStatusCode().value() != 409) throw conflict;
                            return false;
                        }
                    });
            // If the add acquired the user lock before the old membership was revoked,
            // the unique active-membership constraint correctly rejects it. Retry only
            // after both concurrent transactions have completed and released their locks.
            if (!addSucceeded) {
                staffService.addPickupStaff(addOwner.id(), staff.email(), true, true, false);
            }

            boolean hasActiveMembership = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM merchant.merchant_memberships
                                   WHERE user_id=:userId AND status='ACTIVE')
                    """).param("userId", staff.id()).query(Boolean.class).single();
            long activeMembershipCount = jdbc.sql("""
                    SELECT count(*) FROM merchant.merchant_memberships
                    WHERE user_id=:userId AND status='ACTIVE'
                    """).param("userId", staff.id()).query(Long.class).single();
            assertThat(activeMembershipCount).isEqualTo(1);
            boolean hasGlobalRole = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM identity.user_roles
                                   WHERE user_id=:userId AND role='MERCHANT_STAFF')
                    """).param("userId", staff.id()).query(Boolean.class).single();

            assertThat(hasGlobalRole)
                    .as("global MERCHANT_STAFF role must match active memberships after concurrent round %s", round)
                    .isEqualTo(hasActiveMembership);
        }
    }

    @Test
    void enforcesZeroOrOneActiveMembershipAndSynchronizesGlobalRole() {
        User admin = createUser("staff-invariant-admin@example.com", true,
                ma.kulha.identity.domain.model.Role.ADMIN);
        User ownerA = createUser("staff-invariant-owner-a@example.com", true);
        User ownerB = createUser("staff-invariant-owner-b@example.com", true);
        User staff = createUser("staff-invariant-member@example.com", true);

        Merchant merchantA = merchantService.apply(ownerA.id(), profile("Invariant Bakery A", "+212600000011"));
        Merchant merchantB = merchantService.apply(ownerB.id(), profile("Invariant Bakery B", "+212600000012"));
        merchantService.approve(admin.id(), merchantA.id());
        merchantService.approve(admin.id(), merchantB.id());

        staffService.addPickupStaff(ownerA.id(), staff.email(), true, true, false);
        assertThatThrownBy(() -> staffService.addPickupStaff(ownerB.id(), staff.email(), true, true, false))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(409));
        assertMembershipState(staff.id(), 1, true);

        staffService.remove(ownerA.id(), staff.id());
        assertMembershipState(staff.id(), 0, false);

        staffService.addPickupStaff(ownerB.id(), staff.email(), true, true, false);
        assertMembershipState(staff.id(), 1, true);
    }

    private void assertMembershipState(User staff, int expectedCount, boolean expectedRole) {
        assertMembershipState(staff.id(), expectedCount, expectedRole);
    }

    private void assertMembershipState(java.util.UUID staffId, int expectedCount, boolean expectedRole) {
        long activeMembershipCount = jdbc.sql("""
                SELECT count(*) FROM merchant.merchant_memberships
                WHERE user_id=:userId AND status='ACTIVE'
                """).param("userId", staffId).query(Long.class).single();
        boolean hasGlobalRole = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM identity.user_roles
                               WHERE user_id=:userId AND role='MERCHANT_STAFF')
                """).param("userId", staffId).query(Boolean.class).single();
        assertThat(activeMembershipCount).isEqualTo(expectedCount);
        assertThat(hasGlobalRole).isEqualTo(expectedRole);
    }

    private Merchant.Profile profile(String name, String phone) {
        return new Merchant.Profile(name, BusinessType.BAKERY, "12 rue des Fleurs", "Casablanca",
                phone, "Lun-Sam 7h-20h");
    }

    private boolean runConcurrently(ThrowingRunnable first, ThrowingSupplier<Boolean> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> { start.await(); first.run(); return null; });
            var b = executor.submit(() -> { start.await(); return second.get(); });
            start.countDown();
            a.get(20, TimeUnit.SECONDS);
            return b.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
