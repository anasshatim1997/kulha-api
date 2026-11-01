package ma.kulha.admin.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import ma.kulha.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

class AuditLogIntegrationTest extends AbstractApiIntegrationTest {

    @Autowired
    AuditApi audit;

    @Test
    void anEntryIsStoredWithItsActorActionTargetAndReason() {
        UUID actor = UUID.randomUUID();

        audit.append(actor, "MERCHANT_REJECTED", "MERCHANT", "target-1", "Adresse introuvable", "SUCCESS");

        assertThat(auditCount("target-1")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT actor_user_id FROM admin.audit_log WHERE target_id = 'target-1'")
                .query(UUID.class).single()).isEqualTo(actor);
        assertThat(jdbc.sql("SELECT reason FROM admin.audit_log WHERE target_id = 'target-1'")
                .query(String.class).single()).isEqualTo("Adresse introuvable");
    }

    @Test
    void entriesCanNeitherBeChangedNorDeleted() {
        audit.append(UUID.randomUUID(), "MERCHANT_APPROVED", "MERCHANT", "target-2", null, "SUCCESS");

        assertThatThrownBy(() -> jdbc.sql("UPDATE admin.audit_log SET result = 'FAILED' WHERE target_id = 'target-2'").update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM admin.audit_log WHERE target_id = 'target-2'").update())
                .isInstanceOf(DataAccessException.class);
        assertThat(auditCount("target-2")).isEqualTo(1);
    }
}
