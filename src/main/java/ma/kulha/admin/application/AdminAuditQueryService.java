package ma.kulha.admin.application;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import ma.kulha.identity.application.IdentityApi;
import ma.kulha.shared.csv.CsvEscaper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAuditQueryService {
    private static final int EXPORT_BATCH_SIZE = 500;

    private final JdbcClient jdbc;
    private final AuditApi audit;
    private final IdentityApi identities;

    AdminAuditQueryService(JdbcClient jdbc, AuditApi audit, IdentityApi identities) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.identities = identities;
    }

    @Transactional(readOnly = true)
    public AuditPage list(LocalDate from, LocalDate to, UUID actorId, String action, String result,
                          int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.clamp(size, 1, 5000);
        Filter filter = filter(from, to, actorId, action, result);
        List<AuditRow> rows = queryRows(filter, safeSize, safePage * safeSize);
        long total = count(filter);
        return new AuditPage(enrichActorNames(rows), safePage, safeSize, total);
    }

    @Transactional
    public String export(UUID adminId, LocalDate from, LocalDate to, UUID actorId, String action, String result) {
        StringWriter writer = new StringWriter();
        try {
            writeExport(adminId, from, to, actorId, action, result, writer);
        } catch (IOException impossible) {
            throw new IllegalStateException("Writing to an in-memory CSV buffer failed.", impossible);
        }
        return writer.toString();
    }

    @Transactional
    public void writeExport(UUID adminId, LocalDate from, LocalDate to, UUID actorId, String action, String result,
                            Writer writer) throws IOException {
        audit.append(adminId, "ADMIN_AUDIT_EXPORTED", "AUDIT_LOG", "QUERY", "Filtered export", "SUCCESS");
        writer.write("time,actor,action,record,reason,result,correlation_id\n");

        Filter filter = filter(from, to, actorId, action, result);
        OffsetDateTime cursorCreatedAt = null;
        UUID cursorId = null;
        while (true) {
            List<AuditRow> batch = queryExportBatch(filter, cursorCreatedAt, cursorId);
            if (batch.isEmpty()) break;

            for (AuditRow row : enrichActorNames(batch)) {
                writeCsvRow(writer, row);
            }

            AuditRow last = batch.get(batch.size() - 1);
            cursorCreatedAt = last.createdAt().atOffset(ZoneOffset.UTC);
            cursorId = last.id();
            if (batch.size() < EXPORT_BATCH_SIZE) break;
        }
    }

    private static void writeCsvRow(Writer writer, AuditRow row) throws IOException {
        writer.write(row.createdAt().toString());
        writer.write(',');
        writer.write(CsvEscaper.cell(row.actorName()));
        writer.write(',');
        writer.write(CsvEscaper.cell(row.action()));
        writer.write(',');
        writer.write(CsvEscaper.cell(row.targetType() + " · " + row.targetId()));
        writer.write(',');
        writer.write(CsvEscaper.cell(row.reason()));
        writer.write(',');
        writer.write(CsvEscaper.cell(row.result()));
        writer.write(',');
        writer.write(CsvEscaper.cell(row.correlationId()));
        writer.write('\n');
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
        return bind(jdbc.sql("SELECT count(*) FROM admin.audit_log a" + filter.where()), filter)
                .query(Long.class).single();
    }

    private List<AuditRow> queryRows(Filter filter, Integer limit, Integer offset) {
        String pagination = limit == null ? "" : " LIMIT :limit OFFSET :offset";
        JdbcClient.StatementSpec query = bind(jdbc.sql("""
                SELECT a.id, a.actor_user_id, a.action, a.target_type, a.target_id, a.reason,
                       a.result, a.correlation_id, a.created_at
                FROM admin.audit_log a
                """ + filter.where() + " ORDER BY a.created_at DESC, a.id DESC" + pagination), filter);
        if (limit != null) query = query.param("limit", limit).param("offset", offset);
        return query.query(AdminAuditQueryService::mapAuditRow).list();
    }

    private List<AuditRow> queryExportBatch(Filter filter, OffsetDateTime afterCreatedAt, UUID afterId) {
        String cursorClause = afterCreatedAt == null ? "" : " AND (a.created_at, a.id) < (:afterCreatedAt, :afterId)";
        String where = filter.where();
        if (afterCreatedAt != null && where.isEmpty()) where = " WHERE true";
        JdbcClient.StatementSpec query = bind(jdbc.sql("""
                SELECT a.id, a.actor_user_id, a.action, a.target_type, a.target_id, a.reason,
                       a.result, a.correlation_id, a.created_at
                FROM admin.audit_log a
                """ + where + cursorClause + " ORDER BY a.created_at DESC, a.id DESC LIMIT :limit"), filter);
        if (afterCreatedAt != null) {
            query = query.param("afterCreatedAt", afterCreatedAt, Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("afterId", afterId);
        }
        return query.param("limit", EXPORT_BATCH_SIZE).query(AdminAuditQueryService::mapAuditRow).list();
    }

    private static AuditRow mapAuditRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new AuditRow(
                rs.getObject("id", UUID.class), rs.getObject("actor_user_id", UUID.class),
                "Unknown actor", rs.getString("action"), rs.getString("target_type"),
                rs.getString("target_id"), rs.getString("reason"), rs.getString("result"),
                rs.getString("correlation_id"), rs.getObject("created_at", OffsetDateTime.class).toInstant()
        );
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec query, Filter filter) {
        if (filter.from() != null) query = query.param("fromDate", filter.from());
        if (filter.to() != null) query = query.param("toDate", filter.to());
        if (filter.actorId() != null) query = query.param("actorId", filter.actorId());
        if (filter.action() != null && !filter.action().isBlank()) query = query.param("action", filter.action());
        if (filter.result() != null && !filter.result().isBlank()) query = query.param("result", filter.result());
        return query;
    }

    private List<AuditRow> enrichActorNames(List<AuditRow> rows) {
        LinkedHashSet<UUID> actorIds = rows.stream().map(AuditRow::actorUserId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, IdentityApi.SupportUserSummary> profiles = identities.findSupportSummaries(actorIds).stream()
                .collect(Collectors.toMap(IdentityApi.SupportUserSummary::id, profile -> profile));
        return rows.stream().map(row -> {
            IdentityApi.SupportUserSummary profile = row.actorUserId() == null ? null : profiles.get(row.actorUserId());
            return new AuditRow(row.id(), row.actorUserId(),
                    profile == null ? "Unknown actor" : profile.fullName(), row.action(), row.targetType(),
                    row.targetId(), row.reason(), row.result(), row.correlationId(), row.createdAt());
        }).toList();
    }

    private record Filter(String where, LocalDate from, LocalDate to, UUID actorId, String action, String result) {}
    public record AuditRow(UUID id, UUID actorUserId, String actorName, String action, String targetType,
                           String targetId, String reason, String result, String correlationId,
                           java.time.Instant createdAt) {}
    public record AuditPage(List<AuditRow> items, int page, int size, long total) {}
}
