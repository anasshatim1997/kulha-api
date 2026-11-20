package ma.kulha.merchant.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.identity.domain.repository.UserRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import ma.kulha.merchant.domain.model.BusinessType;
import ma.kulha.merchant.domain.model.Merchant;
import ma.kulha.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MerchantApiIntegrationTest extends AbstractApiIntegrationTest {

    @Autowired
    MerchantApi merchantApi;

    @Autowired
    MerchantService merchantService;

    @Autowired
    UserRepository users;

    @Autowired
    JdbcClient jdbc;

    private static Merchant.Profile profile() {
        return new Merchant.Profile("Boulangerie Atlas", BusinessType.BAKERY, "12 rue des Fleurs",
                "Casablanca", "+212600000000", "Lun-Sam 7h-20h");
    }

    @Test
    void aMerchantIsApprovedOnlyAfterTheAdministratorApproves() {
        User owner = createUser("owner@example.com", true);
        User admin = createUser("admin@example.com", true, Role.ADMIN);
        Merchant merchant = merchantService.apply(owner.id(), profile());

        assertThat(merchantApi.isApproved(merchant.id())).isFalse();

        merchantService.approve(admin.id(), merchant.id());
        assertThat(merchantApi.isApproved(merchant.id())).isTrue();

        merchantService.suspend(admin.id(), merchant.id(), "Plaintes");
        assertThat(merchantApi.isApproved(merchant.id())).isFalse();
    }

    @Test
    void theMerchantIdCanBeFoundFromTheOwner() {
        User owner = createUser("owner@example.com", true);
        assertThat(merchantApi.findMerchantIdByOwner(owner.id())).isEmpty();

        Merchant merchant = merchantService.apply(owner.id(), profile());

        assertThat(merchantApi.findMerchantIdByOwner(owner.id())).contains(merchant.id());
        assertThat(merchantApi.isApproved(UUID.randomUUID())).isFalse();
    }
    @Test
    void merchantStaffResolvesOnlyTheMerchantTheyAreAssignedTo() {
        User owner = createUser("staff-owner@example.com", true);
        User admin = createUser("staff-admin@example.com", true, Role.ADMIN);
        User staff = createUser("pickup-staff@example.com", true);
        User otherOwner = createUser("other-merchant-owner@example.com", true);
        Merchant merchant = merchantService.apply(owner.id(), profile());
        merchantService.approve(admin.id(), merchant.id());
        Merchant otherMerchant = merchantService.apply(otherOwner.id(),
                new Merchant.Profile("Cafe Atlas", BusinessType.BAKERY, "45 avenue Hassan II",
                        "Casablanca", "+212600000001", "Lun-Dim 8h-22h"));
        merchantService.approve(admin.id(), otherMerchant.id());

        jdbc.sql("""
                INSERT INTO merchant.merchant_memberships
                    (id, merchant_id, user_id, membership_role, status, invited_by)
                VALUES (:id, :merchantId, :userId, 'PICKUP_STAFF', 'ACTIVE', :ownerId)
                """)
                .param("id", UUID.randomUUID())
                .param("merchantId", merchant.id())
                .param("userId", staff.id())
                .param("ownerId", owner.id())
                .update();
        users.addRole(staff.id(), Role.MERCHANT_STAFF);

        assertThat(merchantApi.resolveActingMerchant(staff.id())).contains(merchant.id())
                .doesNotContain(otherMerchant.id());
        assertThat(merchantApi.resolveActingMerchant(UUID.randomUUID())).isEmpty();
    }

}
