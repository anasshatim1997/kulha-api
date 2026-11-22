package ma.kulha.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Instant;
import java.util.UUID;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

class SupportCaseAssignmentAuthorizationIntegrationTest extends AbstractApiIntegrationTest {

    @AfterEach
    void removeSupportCasesBeforeTheNextIdentityReset() {
        jdbc.sql("DELETE FROM admin.support_cases").update();
    }

    @Test
    void activeSupportStaffCanBeAssignedAndTheChangeIsAudited() throws Exception {
        User customer = createUser("case-customer@example.com", true);
        User admin = createUser("case-admin@example.com", true, Role.ADMIN);
        User support = createUser("support-agent@example.com", true, Role.SUPPORT);
        String reference = createCase(customer);

        MockHttpServletResponse response = assign(reference, support.id(), tokenFor(admin));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(assignedTo(reference)).isEqualTo(support.id());
        assertThat(auditCount(reference)).isEqualTo(1);
        assertThat(timelineCount(reference)).isEqualTo(1);
    }

    @Test
    void activeCustomerCannotBeAssigned() throws Exception {
        User customer = createUser("case-customer@example.com", true);
        User admin = createUser("case-admin@example.com", true, Role.ADMIN);
        User ineligible = createUser("ordinary-customer@example.com", true);
        String reference = createCase(customer);

        MockHttpServletResponse response = assign(reference, ineligible.id(), tokenFor(admin));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(assignedTo(reference)).isNull();
        assertThat(auditCount(reference)).isZero();
        assertThat(timelineCount(reference)).isZero();
    }

    @Test
    void activeMerchantCannotBeAssigned() throws Exception {
        User customer = createUser("case-customer@example.com", true);
        User admin = createUser("case-admin@example.com", true, Role.ADMIN);
        User ineligible = createUser("merchant-user@example.com", true, Role.MERCHANT);
        String reference = createCase(customer);

        MockHttpServletResponse response = assign(reference, ineligible.id(), tokenFor(admin));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(assignedTo(reference)).isNull();
        assertThat(auditCount(reference)).isZero();
    }

    @Test
    void disabledSupportUserCannotBeAssigned() throws Exception {
        User customer = createUser("case-customer@example.com", true);
        User admin = createUser("case-admin@example.com", true, Role.ADMIN);
        User ineligible = createUser("disabled-support@example.com", true, Role.SUPPORT);
        jdbc.sql("UPDATE identity.users SET status='DISABLED' WHERE id=:id").param("id", ineligible.id()).update();
        String reference = createCase(customer);

        MockHttpServletResponse response = assign(reference, ineligible.id(), tokenFor(admin));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(assignedTo(reference)).isNull();
        assertThat(auditCount(reference)).isZero();
    }

    @Test
    void unknownUserCannotBeAssigned() throws Exception {
        User customer = createUser("case-customer@example.com", true);
        User admin = createUser("case-admin@example.com", true, Role.ADMIN);
        String reference = createCase(customer);

        MockHttpServletResponse response = assign(reference, UUID.randomUUID(), tokenFor(admin));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(assignedTo(reference)).isNull();
        assertThat(auditCount(reference)).isZero();
    }

    @Test
    void activeAdministratorsCanBeAssignedWhenTheyAlsoHandleSupportCases() throws Exception {
        User customer = createUser("case-customer@example.com", true);
        User admin = createUser("case-admin@example.com", true, Role.ADMIN);
        User assignee = createUser("second-admin@example.com", true, Role.SUPER_ADMIN);
        String reference = createCase(customer);

        MockHttpServletResponse response = assign(reference, assignee.id(), tokenFor(admin));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(assignedTo(reference)).isEqualTo(assignee.id());
        assertThat(auditCount(reference)).isEqualTo(1);
    }

    @Test
    void onlyAdministratorsCanCallTheAssignmentEndpoint() throws Exception {
        User customer = createUser("case-customer@example.com", true);
        User ordinaryUser = createUser("ordinary-user@example.com", true);
        String reference = createCase(customer);

        assertThat(assign(reference, ordinaryUser.id(), tokenFor(ordinaryUser)).getStatus()).isEqualTo(403);
        assertThat(assign(reference, ordinaryUser.id(), null).getStatus()).isEqualTo(401);
        assertThat(assignedTo(reference)).isNull();
        assertThat(auditCount(reference)).isZero();
    }

    private MockHttpServletResponse assign(String reference, UUID assignee, String token) throws Exception {
        var request = put("/api/v1/admin/support-cases/" + reference + "/assignee")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"assignedTo\":\"" + assignee + "\"}");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return mvc.perform(request).andReturn().getResponse();
    }

    private String createCase(User customer) {
        String reference = "KLH-C-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbc.sql("""
                INSERT INTO admin.support_cases
                    (id, reference, category, subject, description, priority, status, customer_user_id,
                     created_at, updated_at)
                VALUES (:id, :reference, 'ACCOUNT', 'Account help', 'Cannot access my account',
                        'MEDIUM', 'OPEN', :customer, :created, :created)
                """)
                .param("id", UUID.randomUUID())
                .param("reference", reference)
                .param("customer", customer.id())
                .param("created", Instant.now().atOffset(java.time.ZoneOffset.UTC))
                .update();
        return reference;
    }

    private UUID assignedTo(String reference) {
        return jdbc.sql("SELECT assigned_to FROM admin.support_cases WHERE reference=:reference")
                .param("reference", reference)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    private long auditCount(String reference) {
        return jdbc.sql("""
                SELECT count(*) FROM admin.audit_log
                WHERE target_type='SUPPORT_CASE' AND target_id=:reference AND action='SUPPORT_CASE_ASSIGNED'
                """)
                .param("reference", reference).query(Long.class).single();
    }

    private long timelineCount(String reference) {
        return jdbc.sql("""
                SELECT count(*) FROM admin.support_case_timeline t
                JOIN admin.support_cases c ON c.id=t.case_id
                WHERE c.reference=:reference AND t.author_type='SYSTEM'
                """)
                .param("reference", reference).query(Long.class).single();
    }
}
