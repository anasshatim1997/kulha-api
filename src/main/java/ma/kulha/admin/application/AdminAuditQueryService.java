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
        Filter filter = filter(from, to, actorId, action, result);
        List<AuditRow> rows = queryRows(filter, safeSize, safePage * safeSize);
        long total = count(filter);
        return new AuditPage(rows, safePage, safeSize, total);
    }

    @Transactional
    public String export(UUID adminId, LocalDate from, LocalDate to, UUID actorId, String action, String result) {
        audit.append(adminId, "ADMIN_AUDIT_EXPORTED", "AUDIT_LOG", "QUERY", "Filtered export", "SUCCESS");
        List<AuditRow> rows = queryRows(filter(from, to, actorId, action, result), null, null);
        StringBuilder csv = new StringBuilder("time,actor,action,record,reason,result,correlation_id\n");
        for (AuditRow row : rows) {
            csv.append(row.createdAt()).append(',').append(CsvEscaper.cell(row.actorName())).append(',')
                    .append(CsvEscaper.cell(row.action())).append(',')
                    .append(CsvEscaper.cell(row.targetType() + " · " + row.targetId())).append(',')
                    .append(CsvEscaper.cell(row.reason())).append(',').append(CsvEscaper.cell(row.result())).append(',')
                    .append(CsvEscaper.cell(row.correlationId())).append('\n');
        }
        return csv.toString();
    }

    private Filter filter(LocalDate from, LocalDate to, UUID actorId, String action, String result) {
        List<String> clauses = new ArrayList<>();
        if (from != null) clauses.add("(a.created_at AT TIME ZONE 'Africa/Casablanca')::date >= :fromDate");
        if (to != null) clauses.add("(a.created_at AT TIME ZONE 'Africa/Casablanca')::date <= :toDate");
        if (actorId != null) clauses.add("a.actor_user_id = :actorId");
        if (action != null && !action.isBlank()) clauses.add("a.action = :action");
        if (result != null && !result.isBlank()) clauses.add("a.result = :result");
        return new Filter(clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses),
                from, to, actorId, action, result);
    }

    private long count(Filter filter) {
        JdbcClient.StatementSpec query = bind(jdbc.sql("SELECT count(*) FROM admin.audit_log a" + filter.where()), filter);
        return query.query(Long.class).single();
    }

    private List<AuditRow> queryRows(Filter filter, Integer limit, Integer offset) {
        String pagination = limit == null ? "" : " LIMIT :limit OFFSET :offset";
        JdbcClient.StatementSpec query = bind(jdbc.sql("""
                SELECT a.id, a.actor_user_id, coalesce(u.full_name, 'Unknown actor') AS actor_name,
                       a.action, a.target_type, a.target_id, a.reason, a.result, a.correlation_id, a.created_at
                FROM admin.audit_log a LEFT JOIN identity.users u ON u.id=a.actor_user_id
                """ + filter.where() + " ORDER BY a.created_at DESC, a.id DESC" + pagination), filter);
        if (limit != null) query = query.param("limit", limit).param("offset", offset);
        return query.query((rs, row) -> new AuditRow(
                rs.getObject("id", UUID.class), rs.getObject("actor_user_id", UUID.class),
                rs.getString("actor_name"), rs.getString("action"), rs.getString("target_type"),
                rs.getString("target_id"), rs.getString("reason"), rs.getString("result"),
                rs.getString("correlation_id"), rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()
        )).list();
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec query, Filter filter) {
        if (filter.from() != null) query = query.param("fromDate", filter.from());
        if (filter.to() != null) query = query.param("toDate", filter.to());
        if (filter.actorId() != null) query = query.param("actorId", filter.actorId());
        if (filter.action() != null && !filter.action().isBlank()) query = query.param("action", filter.action());
        if (filter.result() != null && !filter.result().isBlank()) query = query.param("result", filter.result());
        return query;
    }

    private record Filter(String where, LocalDate from, LocalDate to, UUID actorId, String action, String result) {}

    public record AuditRow(UUID id, UUID actorUserId, String actorName, String action, String targetType,
                           String targetId, String reason, String result, String correlationId,
                           java.time.Instant createdAt) {}
    public record AuditPage(List<AuditRow> items, int page, int size, long total) {}
}
