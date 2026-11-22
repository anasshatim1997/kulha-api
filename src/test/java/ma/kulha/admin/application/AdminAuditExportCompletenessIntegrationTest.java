package ma.kulha.admin.application;

import static org.assertj.core.api.Assertions.assertThat;

import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AdminAuditExportCompletenessIntegrationTest extends AbstractApiIntegrationTest {

    @Autowired
    AdminAuditQueryService auditQuery;

    @Test
    void exportDoesNotSilentlyTruncateAtFiveThousandRows() {
        User admin = createUser("audit-export-admin@example.com", true, Role.ADMIN);
        jdbc.sql("""
                INSERT INTO admin.audit_log
                    (id, actor_user_id, action, target_type, target_id, reason, result, correlation_id, created_at)
                SELECT gen_random_uuid(), :actor, 'BULK_EXPORT_TEST', 'TEST', 'row-' || n,
                       NULL, 'SUCCESS', 'bulk-' || n, now() - (n * interval '1 second')
                FROM generate_series(1, 5001) AS series(n)
                """)
                .param("actor", admin.id())
                .update();

        String csv = auditQuery.export(admin.id(), null, null, null, "BULK_EXPORT_TEST", null);

        assertThat(csv.split("\\R")).hasSize(5002);
        assertThat(csv).contains("row-1").contains("row-5001");
    }

    @Test
    void freeTextSearchFiltersTheAuditPageAndStreamingExport() throws Exception {
        User admin = createUser("audit-search-admin@example.com", true, Role.ADMIN);
        jdbc.sql("""
                INSERT INTO admin.audit_log
                    (id, actor_user_id, action, target_type, target_id, reason, result, correlation_id, created_at)
                VALUES
                    (gen_random_uuid(), :actor, 'MERCHANT_REVIEWED', 'MERCHANT', 'matching-target',
                     'inspection keyword', 'SUCCESS', 'search-match', now()),
                    (gen_random_uuid(), :actor, 'OFFER_PUBLISHED', 'OFFER', 'unrelated-target',
                     'ordinary update', 'SUCCESS', 'search-other', now())
                """)
                .param("actor", admin.id())
                .update();

        AdminAuditQueryService.AuditPage page =
                auditQuery.list(null, null, null, null, null, "INSPECTION KEYWORD", 0, 20);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().targetId()).isEqualTo("matching-target");

        java.io.StringWriter csv = new java.io.StringWriter();
        auditQuery.writeExport(admin.id(), null, null, null, null, null, "inspection keyword", csv);
        assertThat(csv.toString()).contains("matching-target").doesNotContain("unrelated-target");
    }

}
