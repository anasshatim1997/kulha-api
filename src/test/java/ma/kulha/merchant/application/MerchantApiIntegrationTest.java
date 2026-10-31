package ma.kulha.merchant.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
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
}
