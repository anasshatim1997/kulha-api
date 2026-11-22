package ma.kulha.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class SupportCaseAccessAndDisclosureIntegrationTest extends AbstractApiIntegrationTest {

    @AfterEach
    void cleanSupportCases() {
        jdbc.sql("DELETE FROM admin.support_cases").update();
    }

    @Test
    void supportAgentCanOnlyListAndReadCasesAssignedToThem() throws Exception {
        User customer = createUser("scope-customer@example.com", true);
        User support = createUser("scope-support@example.com", true, Role.SUPPORT);
        User otherSupport = createUser("other-support@example.com", true, Role.SUPPORT);
        String assigned = createCase(customer);
        String notAssigned = createCase(customer);
        assignDirectly(assigned, support.id());
        assignDirectly(notAssigned, otherSupport.id());

        mvc.perform(get("/api/v1/admin/support-cases").header("Authorization", bearer(support)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].reference").value(assigned));

        mvc.perform(get("/api/v1/admin/support-cases/" + assigned)
                        .header("Authorization", bearer(support)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.customerLabel").value(org.hamcrest.Matchers.not(customer.fullName())))
                .andExpect(jsonPath("$.summary.email").doesNotExist());

        mvc.perform(get("/api/v1/admin/support-cases/" + notAssigned)
                        .header("Authorization", bearer(support)))
                .andExpect(status().isNotFound());
    }

    @Test
    void supportAgentCannotReassignCases() throws Exception {
        User customer = createUser("assignment-customer@example.com", true);
        User support = createUser("assignment-support@example.com", true, Role.SUPPORT);
        String reference = createCase(customer);
        assignDirectly(reference, support.id());
        User otherSupport = createUser("assignment-other@example.com", true, Role.SUPPORT);

        mvc.perform(put("/api/v1/admin/support-cases/" + reference + "/assignee")
                        .header("Authorization", bearer(support))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\\"assignedTo\\":\\""+otherSupport.id()+"\\"}"))
                .andExpect(status().isForbidden());

        assertThat(jdbc.sql("SELECT assigned_to FROM admin.support_cases WHERE reference=:reference")
                .param("reference", reference).query(UUID.class).single()).isEqualTo(support.id());
    }

    @Test
    void assignedSupportAgentCanRevealCustomerOnlyWithReasonAndAuditEntry() throws Exception {
        User customer = createUser("disclosure-customer@example.com", true);
        User support = createUser("disclosure-support@example.com", true, Role.SUPPORT);
        String reference = createCase(customer);
        assignDirectly(reference, support.id());

        mvc.perform(post("/api/v1/admin/support-cases/" + reference + "/reveal-customer")
                        .header("Authorization", bearer(support))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Customer requested an account ownership check\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(customer.email()));

        assertThat(disclosureAuditCount(reference)).isEqualTo(1);
        assertThat(disclosureAuditReason(reference))
                .isEqualTo("Customer requested an account ownership check");
    }

    @Test
    void supportAgentCannotRevealUnassignedCaseOrOmitDisclosureReason() throws Exception {
        User customer = createUser("denied-disclosure-customer@example.com", true);
        User support = createUser("denied-disclosure-support@example.com", true, Role.SUPPORT);
        String reference = createCase(customer);
        assignDirectly(reference, createUser("another-agent@example.com", true, Role.SUPPORT).id());

        mvc.perform(post("/api/v1/admin/support-cases/" + reference + "/reveal-customer")
                        .header("Authorization", bearer(support))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Need to contact customer\"}"))
                .andExpect(status().isNotFound());

        String ownReference = createCase(customer);
        assignDirectly(ownReference, support.id());
        mvc.perform(post("/api/v1/admin/support-cases/" + ownReference + "/reveal-customer")
                        .header("Authorization", bearer(support))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());

        assertThat(disclosureAuditCount(reference)).isZero();
        assertThat(disclosureAuditCount(ownReference)).isZero();
    }

    @Test
    void adminCannotRevealCustomerButSuperAdminCanRevealAnyCase() throws Exception {
        User customer = createUser("admin-pii-customer@example.com", true);
        User admin = createUser("pii-admin@example.com", true, Role.ADMIN);
        User superAdmin = createUser("pii-superadmin@example.com", true, Role.SUPER_ADMIN);
        String reference = createCase(customer);

        mvc.perform(post("/api/v1/admin/support-cases/" + reference + "/reveal-customer")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Operational review\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/admin/support-cases/" + reference + "/reveal-customer")
                        .header("Authorization", bearer(superAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Escalated identity verification\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(customer.email()));

        assertThat(disclosureAuditCount(reference)).isEqualTo(1);
        assertThat(disclosureAuditReason(reference)).isEqualTo("Escalated identity verification");
    }

    private String bearer(User user) throws Exception {
        return "Bearer " + tokenFor(user);
    }

    private String createCase(User customer) {
        String reference = "KLH-C-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        var now = Instant.now().atOffset(java.time.ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO admin.support_cases
                    (id, reference, category, subject, description, priority, status, customer_user_id,
                     created_at, updated_at)
                VALUES (:id, :reference, 'ACCOUNT', 'Account help', 'Cannot access my account',
                        'MEDIUM', 'OPEN', :customer, :created, :created)
                """)
                .param("id", UUID.randomUUID()).param("reference", reference)
                .param("customer", customer.id()).param("created", now).update();
        return reference;
    }

    private void assignDirectly(String reference, UUID assignee) {
        jdbc.sql("UPDATE admin.support_cases SET assigned_to=:assignee WHERE reference=:reference")
                .param("assignee", assignee).param("reference", reference).update();
    }

    private long disclosureAuditCount(String reference) {
        return jdbc.sql("""
                SELECT count(*) FROM admin.audit_log
                WHERE target_type='SUPPORT_CASE' AND target_id=:reference
                  AND action='SUPPORT_CUSTOMER_DETAILS_REVEALED'
                """).param("reference", reference).query(Long.class).single();
    }

    private String disclosureAuditReason(String reference) {
        return jdbc.sql("""
                SELECT reason FROM admin.audit_log
                WHERE target_type='SUPPORT_CASE' AND target_id=:reference
                  AND action='SUPPORT_CUSTOMER_DETAILS_REVEALED'
                """).param("reference", reference).query(String.class).single();
    }
}
