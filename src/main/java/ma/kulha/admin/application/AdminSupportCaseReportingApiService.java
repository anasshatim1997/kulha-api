package ma.kulha.admin.application;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdminSupportCaseReportingApiService implements AdminSupportCaseReportingApi {
    private final JdbcClient jdbc;

    AdminSupportCaseReportingApiService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Long> openCaseCountsByOfferIds(Collection<UUID> offerIds) {
        if (offerIds == null || offerIds.isEmpty()) return Map.of();
        return jdbc.sql("""
                SELECT offer_id, count(*) AS open_count
                FROM admin.support_cases
                WHERE offer_id IN (:ids) AND status <> 'RESOLVED'
                GROUP BY offer_id
                """).param("ids", offerIds)
                .query((rs, row) -> Map.entry(rs.getObject("offer_id", UUID.class), rs.getLong("open_count")))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
