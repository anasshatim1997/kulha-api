package ma.kulha.merchant.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class MerchantApplicationIntegrationTest extends AbstractApiIntegrationTest {

    @Test
    void applyCreatesAPendingMerchantAndGivesNoMerchantRightsYet() throws Exception {
        User owner = createUser("owner@example.com", true);
        String token = tokenFor(owner);

        MockHttpServletResponse response = doPost("/api/v1/merchants/apply", profileJson("Boulangerie Atlas"), token);

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentAsString()).contains("\"status\":\"PENDING\"").contains("Boulangerie Atlas");
        String me = doGet("/api/v1/me", token).getContentAsString();
        assertThat(me).contains("CUSTOMER").doesNotContain("MERCHANT");
    }

    @Test
    void applyWithoutLoggingInReturns401() throws Exception {
        assertThat(doPost("/api/v1/merchants/apply", profileJson("Boulangerie Atlas"), null).getStatus())
                .isEqualTo(401);
    }

    @Test
    void applyWithAnUnverifiedEmailReturns403() throws Exception {
        User owner = createUser("unverified@example.com", false);

        MockHttpServletResponse response =
                doPost("/api/v1/merchants/apply", profileJson("Boulangerie Atlas"), tokenFor(owner));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM merchant.merchants").query(Long.class).single()).isZero();
    }

    @Test
    void applyWithInvalidDataReturns400() throws Exception {
        String token = tokenFor(createUser("owner@example.com", true));

        assertThat(doPost("/api/v1/merchants/apply", profileJson("   "), token).getStatus()).isEqualTo(400);
        assertThat(doPost("/api/v1/merchants/apply",
                profileJson("Boulangerie").replace("+212600000000", "not-a-phone"), token).getStatus())
                .isEqualTo(400);
        assertThat(doPost("/api/v1/merchants/apply",
                profileJson("Boulangerie").replace("BAKERY", "SPACESHIP"), token).getStatus())
                .isEqualTo(400);
    }

    @Test
    void applyingTwiceReturns409() throws Exception {
        String token = tokenFor(createUser("owner@example.com", true));
        doPost("/api/v1/merchants/apply", profileJson("Boulangerie Atlas"), token);

        assertThat(doPost("/api/v1/merchants/apply", profileJson("Autre nom"), token).getStatus()).isEqualTo(409);
    }

    @Test
    void getOwnProfileReturns404UntilTheUserHasApplied() throws Exception {
        String token = tokenFor(createUser("owner@example.com", true));
        assertThat(doGet("/api/v1/merchants/me", token).getStatus()).isEqualTo(404);

        doPost("/api/v1/merchants/apply", profileJson("Boulangerie Atlas"), token);

        MockHttpServletResponse response = doGet("/api/v1/merchants/me", token);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("Boulangerie Atlas");
    }

    @Test
    void updateOwnProfileChangesTheDetailsAndKeepsTheStatus() throws Exception {
        String token = tokenFor(createUser("owner@example.com", true));
        doPost("/api/v1/merchants/apply", profileJson("Boulangerie Atlas"), token);

        MockHttpServletResponse response = doPut(profileJson("Boulangerie Atlas 2"), token);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("Boulangerie Atlas 2").contains("\"status\":\"PENDING\"");
    }

    @Test
    void aRejectedMerchantThatCorrectsItsProfileIsResubmittedAsPending() throws Exception {
        String merchantId = applyAs("owner@example.com", "Boulangerie Atlas");
        String adminToken = tokenFor(createUser("admin@example.com", true, Role.ADMIN));
        doPost("/api/v1/admin/merchants/" + merchantId + "/reject", "{\"reason\":\"Adresse introuvable\"}", adminToken);
        String ownerToken = tokenForEmail("owner@example.com");
        assertThat(doGet("/api/v1/merchants/me", ownerToken).getContentAsString()).contains("REJECTED");

        MockHttpServletResponse response = doPut(profileJson("Boulangerie Atlas"), ownerToken);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"status\":\"PENDING\"").contains("\"decisionReason\":null");
    }

    @Test
    void aSuspendedMerchantCannotEditItsProfile() throws Exception {
        String merchantId = applyAs("owner@example.com", "Boulangerie Atlas");
        String adminToken = tokenFor(createUser("admin@example.com", true, Role.ADMIN));
        doPostEmpty("/api/v1/admin/merchants/" + merchantId + "/approve", adminToken);
        doPost("/api/v1/admin/merchants/" + merchantId + "/suspend", "{\"reason\":\"Plaintes\"}", adminToken);

        MockHttpServletResponse response =
                doPut(profileJson("Autre"), tokenForEmail("owner@example.com"));

        assertThat(response.getStatus()).isEqualTo(409);
    }

    @Test
    void eachUserOnlyEverSeesItsOwnMerchant() throws Exception {
        applyAs("first@example.com", "Boulangerie Premiere");
        applyAs("second@example.com", "Cafe Seconde");

        String first = doGet("/api/v1/merchants/me", tokenForEmail("first@example.com")).getContentAsString();
        String second = doGet("/api/v1/merchants/me", tokenForEmail("second@example.com")).getContentAsString();

        assertThat(first).contains("Boulangerie Premiere").doesNotContain("Cafe Seconde");
        assertThat(second).contains("Cafe Seconde").doesNotContain("Boulangerie Premiere");
    }
}
