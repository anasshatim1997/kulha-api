package ma.kulha.merchant.application;

import static org.assertj.core.api.Assertions.assertThat;

import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.merchant.domain.model.BusinessType;
import ma.kulha.merchant.domain.model.Merchant;
import ma.kulha.support.AbstractApiIntegrationTest;
import net.ttddyy.dsproxy.QueryCountHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import(MerchantStaffQueryCountTestConfiguration.class)
class MerchantStaffQueryCountIntegrationTest extends AbstractApiIntegrationTest {
    @Autowired MerchantService merchantService;
    @Autowired MerchantStaffService staffService;

    @Test
    void staffListUsesConstantNumberOfQueriesAsTeamSizeGrows() {
        User owner = createUser("query-count-owner@example.com", true);
        User admin = createUser("query-count-admin@example.com", true, Role.ADMIN);
        Merchant merchant = merchantService.apply(owner.id(), new Merchant.Profile(
                "Boulangerie Atlas", BusinessType.BAKERY, "12 rue des Fleurs", "Casablanca",
                "+212600000099", "Lun-Sam 7h-20h"));
        merchantService.approve(admin.id(), merchant.id());

        addStaff(owner, 2, 0);
        long smallTeamQueries = countListQueries(owner);

        addStaff(owner, 20, 2);
        long largeTeamQueries = countListQueries(owner);

        assertThat(smallTeamQueries)
                .as("merchant lookup + membership page + one batched identity lookup")
                .isEqualTo(3);
        assertThat(largeTeamQueries)
                .as("adding staff must not add one identity query per membership")
                .isEqualTo(smallTeamQueries);
    }

    private void addStaff(User owner, int count, int offset) {
        for (int i = 0; i < count; i++) {
            User staff = createUser("query-count-staff-" + (offset + i) + "@example.com", true);
            staffService.addPickupStaff(owner.id(), staff.email(), true, true, false);
        }
    }

    private long countListQueries(User owner) {
        QueryCountHolder.clear();
        var result = staffService.list(owner.id());
        assertThat(result).isNotEmpty();
        var counts = QueryCountHolder.get("merchant-staff-query-count");
        assertThat(counts).as("datasource-proxy must capture JDBC executions").isNotNull();
        return counts.getTotal();
    }
}
