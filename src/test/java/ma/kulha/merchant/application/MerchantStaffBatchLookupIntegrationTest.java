package ma.kulha.merchant.application;

import static org.assertj.core.api.Assertions.assertThat;

import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.merchant.domain.model.BusinessType;
import ma.kulha.merchant.domain.model.Merchant;
import ma.kulha.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MerchantStaffBatchLookupIntegrationTest extends AbstractApiIntegrationTest {

    @Autowired
    MerchantService merchantService;

    @Autowired
    MerchantStaffService staffService;

    @Test
    void listsOwnerAndMultipleStaffAccountsWithTheirCurrentPermissions() {
        User owner = createUser("batch-owner@example.com", true);
        User admin = createUser("batch-admin@example.com", true, Role.ADMIN);
        User firstStaff = createUser("first-staff@example.com", true);
        User secondStaff = createUser("second-staff@example.com", true);
        Merchant merchant = merchantService.apply(owner.id(), new Merchant.Profile(
                "Boulangerie Atlas", BusinessType.BAKERY, "12 rue des Fleurs", "Casablanca",
                "+212600000000", "Lun-Sam 7h-20h"));
        merchantService.approve(admin.id(), merchant.id());

        staffService.addPickupStaff(owner.id(), firstStaff.email(), true, true, false);
        staffService.addPickupStaff(owner.id(), secondStaff.email(), false, true, true);

        var team = staffService.list(owner.id());

        assertThat(team).hasSize(3);
        assertThat(team).extracting(MerchantStaffService.StaffMember::email)
                .containsExactlyInAnyOrder(owner.email(), firstStaff.email(), secondStaff.email());
        assertThat(team).filteredOn(member -> member.userId().equals(firstStaff.id()))
                .singleElement().satisfies(member -> {
                    assertThat(member.canValidatePickups()).isTrue();
                    assertThat(member.canViewOrders()).isTrue();
                    assertThat(member.canManageOffers()).isFalse();
                });
        assertThat(team).filteredOn(member -> member.userId().equals(secondStaff.id()))
                .singleElement().satisfies(member -> {
                    assertThat(member.canValidatePickups()).isFalse();
                    assertThat(member.canViewOrders()).isTrue();
                    assertThat(member.canManageOffers()).isTrue();
                });
    }
}
