package ma.kulha.admin.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import ma.kulha.shared.csv.CsvEscaper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAuditQueryService {
    private final JdbcClient jdbc;
    private final AuditApi audit;

    AdminAuditQueryService(JdbcClient jdbc, AuditApi audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public AuditPage list(LocalDate from, LocalDate to, UUID actorId, String action, String result,
                          int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.clamp(size, 1, 5000);
        List<String> clauses = new ArrayList<>();
        if (from != null) clauses.add("(a.created_at AT TIME ZONE 'Africa/Casablanca')::date >= :fromDate");
        if (to != null) clauses.add("(a.created_at AT TIME ZONE 'Africa/Casablanca')::date <= :toDate");
        if (actorId != null) clauses.add("a.actor_user_id = :actorId");
        if (action != null && !action.isBlank()) clauses.add("a.action = :action");
        if (result != null && !result.isBlank()) clauses.add("a.result = :result");
        String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
        JdbcClient.StatementSpec count = jdbc.sql("SELECT count(*) FROM admin.audit_log a" + where);
        JdbcClient.StatementSpec query = jdbc.sql("""
                SELECT a.id, a.actor_user_id, coalesce(u.full_name, 'Unknown actor') AS actor_name,
                       a.action, a.target_type, a.target_id, a.reason, a.result, a.correlation_id, a.created_at
                FROM admin.audit_log a LEFT JOIN identity.users u ON u.id=a.actor_user_id
                """ + where + " ORDER BY a.created_at DESC, a.id DESC LIMIT :limit OFFSET :offset")
                .param("limit", safeSize).param("offset", safePage * safeSize);
        if (from != null) { count=count.param("fromDate",from); query=query.param("fromDate",from); }
        if (to != null) { count=count.param("toDate",to); query=query.param("toDate",to); }
        if (actorId != null) { count=count.param("actorId",actorId); query=query.param("actorId",actorId); }
        if (action != null && !action.isBlank()) { count=count.param("action",action); query=query.param("action",action); }
        if (result != null && !result.isBlank()) { count=count.param("result",result); query=query.param("result",result); }
        List<AuditRow> rows = query.query((rs, row) -> new AuditRow(
                rs.getObject("id", UUID.class), rs.getObject("actor_user_id", UUID.class),
                rs.getString("actor_name"), rs.getString("action"), rs.getString("target_type"),
                rs.getString("target_id"), rs.getString("reason"), rs.getString("result"),
                rs.getString("correlation_id"), rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()
        )).list();
        long total = count.query(Long.class).single();
        return new AuditPage(rows, safePage, safeSize, total);
    }

    @Transactional
    public String export(UUID adminId, LocalDate from, LocalDate to, UUID actorId, String action, String result) {
        audit.append(adminId, "ADMIN_AUDIT_EXPORTED", "AUDIT_LOG", "QUERY", "Filtered export", "SUCCESS");
        AuditPage page = list(from, to, actorId, action, result, 0, 5000);
        StringBuilder csv = new StringBuilder("time,actor,action,record,reason,result,correlation_id\n");
        for (AuditRow row : page.items()) {
            csv.append(row.createdAt()).append(',').append(CsvEscaper.cell(row.actorName())).append(',')
                    .append(CsvEscaper.cell(row.action())).append(',')
                    .append(CsvEscaper.cell(row.targetType() + " · " + row.targetId())).append(',')
                    .append(CsvEscaper.cell(row.reason())).append(',').append(CsvEscaper.cell(row.result())).append(',')
                    .append(CsvEscaper.cell(row.correlationId())).append('\n');
        }
        return csv.toString();
    }

    public record AuditRow(UUID id, UUID actorUserId, String actorName, String action, String targetType,
                           String targetId, String reason, String result, String correlationId,
                           java.time.Instant createdAt) {}
    public record AuditPage(List<AuditRow> items, int page, int size, long total) {}
}
