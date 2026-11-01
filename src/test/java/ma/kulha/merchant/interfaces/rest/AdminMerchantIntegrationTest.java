
package ma.kulha.merchant.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.merchant.application.MerchantService;
import ma.kulha.merchant.domain.model.InvalidMerchantStateException;
import ma.kulha.notification.application.TemplateKey;
import ma.kulha.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminMerchantIntegrationTest extends AbstractApiIntegrationTest {

    @Autowired
    MerchantService merchantService;

    private String adminToken() throws Exception {
        return tokenFor(createUser("admin@example.com", true, Role.ADMIN));
    }

    private String approvePath(String merchantId) {
        return "/api/v1/admin/merchants/" + merchantId + "/approve";
    }

    private String rejectPath(String merchantId) {
        return "/api/v1/admin/merchants/" + merchantId + "/reject";
    }

    private String suspendPath(String merchantId) {
        return "/api/v1/admin/merchants/" + merchantId + "/suspend";
    }

    @Test
    void adminEndpointsAreClosedToCustomersAndAnonymousCallers() throws Exception {
        String customerToken = tokenFor(createUser("customer@example.com", true));

        assertThat(doGet("/api/v1/admin/merchants", customerToken).getStatus()).isEqualTo(403);
        assertThat(doGet("/api/v1/admin/merchants", null).getStatus()).isEqualTo(401);
        assertThat(doPostEmpty(approvePath(UUID.randomUUID().toString()), customerToken).getStatus()).isEqualTo(403);
    }

    @Test
    void aMerchantOwnerIsNotAnAdministratorEither() throws Exception {
        String merchantId = applyAs("owner@example.com", "Boulangerie Atlas");
        String adminToken = adminToken();
        doPostEmpty(approvePath(merchantId), adminToken);

        String ownerToken = tokenForEmail("owner@example.com");

        assertThat(doPostEmpty(approvePath(merchantId), ownerToken).getStatus()).isEqualTo(403);
    }

    @Test
    void approveMakesTheOwnerAMerchantWritesTheAuditAndSendsTheEmail() throws Exception {
        String merchantId = applyAs("owner@example.com", "Boulangerie Atlas");
        String ownerToken = tokenForEmail("owner@example.com");
        users.findByEmail("admin@example.com");
        User admin;
        String adminToken = adminToken();
        admin = users.findByEmail("admin@example.com").orElseThrow();

        MockHttpServletResponse response = doPostEmpty(approvePath(merchantId), adminToken);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"status\":\"APPROVED\"").contains("owner@example.com");
        // The same access token now carries the merchant role because roles are read from the database.
        assertThat(doGet("/api/v1/me", ownerToken).getContentAsString()).contains("MERCHANT");
        assertThat(auditCount(merchantId)).isEqualTo(1);
        String action = jdbc.sql("SELECT action FROM admin.audit_log WHERE target_id = :id")
                .param("id", merchantId).query(String.class).single();
        assertThat(action).isEqualTo("MERCHANT_APPROVED");
        UUID actor = jdbc.sql("SELECT actor_user_id FROM admin.audit_log WHERE target_id = :id")
                .param("id", merchantId).query(UUID.class).single();
        assertThat(actor).isEqualTo(admin.id());
        assertThat(emails.sent()).hasSize(1);
        assertThat(emails.sent().getFirst().to()).isEqualTo("owner@example.com");
        assertThat(emails.sent().getFirst().template()).isEqualTo(TemplateKey.MERCHANT_APPROVED);
    }

    @Test
    void rejectRequiresAReason() throws Exception {
        String merchantId = applyAs("owner@example.com", "Boulangerie Atlas");
        String adminToken = adminToken();

        assertThat(doPost(rejectPath(merchantId), "{\"reason\":\"\"}", adminToken).getStatus()).isEqualTo(400);
        assertThat(doPost(rejectPath(merchantId), "{}", adminToken).getStatus()).isEqualTo(400);

        assertThat(doGet("/api/v1/admin/merchants/" + merchantId, adminToken).getContentAsString())
                .contains("\"status\":\"PENDING\"");
    }

    @Test
    void rejectStoresTheReasonEmailsItAndGrantsNoRights() throws Exception {
        String merchantId = applyAs("owner@example.com", "Boulangerie Atlas");
        String ownerToken = tokenForEmail("owner@example.com");

        MockHttpServletResponse response =
                doPost(rejectPath(merchantId), "{\"reason\":\"Adresse introuvable\"}", adminToken());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"status\":\"REJECTED\"").contains("Adresse introuvable");
        assertThat(doGet("/api/v1/me", ownerToken).getContentAsString()).doesNotContain("MERCHANT");
        assertThat(emails.sent().getFirst().template()).isEqualTo(TemplateKey.MERCHANT_REJECTED);
        assertThat(emails.sent().getFirst().params().get("reason")).isEqualTo("Adresse introuvable");
        String reason = jdbc.sql("SELECT reason FROM admin.audit_log WHERE target_id = :id")
                .param("id", merchantId).query(String.class).single();
        assertThat(reason).isEqualTo("Adresse introuvable");
    }

    @Test
    void decidingTwiceIsRefusedWithConflict() throws Exception {
        String merchantId = applyAs("owner@example.com", "Boulangerie Atlas");
        String adminToken = adminToken();
        assertThat(doPostEmpty(approvePath(merchantId), adminToken).getStatus()).isEqualTo(200);

        assertThat(doPostEmpty(approvePath(merchantId), adminToken).getStatus()).isEqualTo(409);
        assertThat(doPost(rejectPath(merchantId), "{\"reason\":\"Trop tard\"}", adminToken).getStatus())
                .isEqualTo(409);
        assertThat(auditCount(merchantId)).isEqualTo(1);
    }

    @Test
    void suspendRemovesTheMerchantRoleAndApprovingAgainRestoresIt() throws Exception {
        String merchantId = applyAs("owner@example.com", "Boulangerie Atlas");
        String ownerToken = tokenForEmail("owner@example.com");
        String adminToken = adminToken();
        doPostEmpty(approvePath(merchantId), adminToken);
        assertThat(doGet("/api/v1/me", ownerToken).getContentAsString()).contains("MERCHANT");

        MockHttpServletResponse suspended = doPost(suspendPath(merchantId), "{\"reason\":\"Plaintes\"}", adminToken);

        assertThat(suspended.getStatus()).isEqualTo(200);
        assertThat(suspended.getContentAsString()).contains("\"status\":\"SUSPENDED\"");
        assertThat(doGet("/api/v1/me", ownerToken).getContentAsString()).doesNotContain("MERCHANT");

        assertThat(doPostEmpty(approvePath(merchantId), adminToken).getStatus()).isEqualTo(200);
        assertThat(doGet("/api/v1/me", ownerToken).getContentAsString()).contains("MERCHANT");
        assertThat(auditCount(merchantId)).isEqualTo(3);
    }

    @Test
    void suspendNeedsAnApprovedMerchant() throws Exception {
        String merchantId = applyAs("owner@example.com", "Boulangerie Atlas");

        assertThat(doPost(suspendPath(merchantId), "{\"reason\":\"Plaintes\"}", adminToken()).getStatus())
                .isEqualTo(409);
    }

    @Test
    void anUnknownMerchantReturns404() throws Exception {
        String adminToken = adminToken();
        String unknown = UUID.randomUUID().toString();

        assertThat(doGet("/api/v1/admin/merchants/" + unknown, adminToken).getStatus()).isEqualTo(404);
        assertThat(doPostEmpty(approvePath(unknown), adminToken).getStatus()).isEqualTo(404);
    }

    @Test
    void listFiltersByStatusAndShowsTheOwnerEmail() throws Exception {
        String approvedId = applyAs("first@example.com", "Boulangerie Premiere");
        applyAs("second@example.com", "Cafe Seconde");
        String adminToken = adminToken();
        doPostEmpty(approvePath(approvedId), adminToken);

        String pending = doGet("/api/v1/admin/merchants?status=PENDING", adminToken).getContentAsString();
        String approved = doGet("/api/v1/admin/merchants?status=APPROVED", adminToken).getContentAsString();
        String all = doGet("/api/v1/admin/merchants", adminToken).getContentAsString();

        assertThat(pending).contains("Cafe Seconde").contains("second@example.com")
                .doesNotContain("Boulangerie Premiere").contains("\"total\":1");
        assertThat(approved).contains("Boulangerie Premiere").doesNotContain("Cafe Seconde");
        assertThat(all).contains("\"total\":2");
    }

    @Test
    void thePageSizeIsCapped() throws Exception {
        String body = doGet("/api/v1/admin/merchants?size=1000", adminToken()).getContentAsString();

        assertThat(body).contains("\"size\":50");
    }

    @Test
    void anUnknownStatusFilterReturns400() throws Exception {
        assertThat(doGet("/api/v1/admin/merchants?status=NOPE", adminToken()).getStatus()).isEqualTo(400);
    }

    @Test
    void whenTwoAdministratorsDecideAtTheSameTimeExactlyOneDecisionWins() throws Exception {
        User admin1 = createUser("admin1@example.com", true, Role.ADMIN);
        User admin2 = createUser("admin2@example.com", true, Role.ADMIN);
        UUID merchantId = UUID.fromString(applyAs("owner@example.com", "Boulangerie Atlas"));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> approve = () -> {
            start.await();
            try {
                merchantService.approve(admin1.id(), merchantId);
                return true;
            } catch (InvalidMerchantStateException lostTheRace) {
                return false;
            }
        };
        Callable<Boolean> reject = () -> {
            start.await();
            try {
                merchantService.reject(admin2.id(), merchantId, "Dossier incomplet");
                return true;
            } catch (InvalidMerchantStateException lostTheRace) {
                return false;
            }
        };
        Future<Boolean> first = executor.submit(approve);
        Future<Boolean> second = executor.submit(reject);
        start.countDown();
        boolean firstWon = first.get(30, TimeUnit.SECONDS);
        boolean secondWon = second.get(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(firstWon ^ secondWon).as("exactly one decision wins").isTrue();
        assertThat(auditCount(merchantId.toString())).isEqualTo(1);
    }
}
