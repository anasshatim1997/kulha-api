package ma.kulha.merchant.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.kulha.merchant.domain.model.BusinessType;
import ma.kulha.merchant.domain.model.Merchant;
import ma.kulha.merchant.domain.model.MerchantStatus;
import ma.kulha.merchant.domain.repository.MerchantRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcMerchantRepository implements MerchantRepository {

    private static final String SELECT_MERCHANT = """
            SELECT id, owner_user_id, name, business_type, address, city, district, latitude, longitude, phone, opening_hours, pickup_instructions,
                   status, decision_reason, decided_by, decided_at, created_at, updated_at
            FROM merchant.merchants
            """;

    private final JdbcClient jdbc;

    JdbcMerchantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Merchant m) {
        jdbc.sql("""
                INSERT INTO merchant.merchants
                    (id, owner_user_id, name, business_type, address, city, district, latitude, longitude, phone, opening_hours, pickup_instructions,
                     status, decision_reason, decided_by, decided_at, created_at, updated_at)
                VALUES
                    (:id, :owner, :name, :type, :address, :city, :district, :latitude, :longitude, :phone, :hours, :pickupInstructions,
                     :status, :reason, :decidedBy, :decidedAt, :createdAt, :updatedAt)
                """)
                .param("id", m.id())
                .param("owner", m.ownerUserId())
                .param("name", m.name())
                .param("type", m.businessType().name())
                .param("address", m.address())
                .param("city", m.city())
                .param("district", m.district(), Types.VARCHAR)
                .param("latitude", m.latitude(), Types.DOUBLE)
                .param("longitude", m.longitude(), Types.DOUBLE)
                .param("phone", m.phone())
                .param("hours", m.openingHours())
                .param("pickupInstructions", m.pickupInstructions(), Types.VARCHAR)
                .param("status", m.status().name())
                .param("reason", m.decisionReason(), Types.VARCHAR)
                .param("decidedBy", m.decidedBy(), Types.OTHER)
                .param("decidedAt", toDb(m.decidedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("createdAt", toDb(m.createdAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("updatedAt", toDb(m.updatedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    @Override
    public Optional<Merchant> findById(UUID id) {
        return jdbc.sql(SELECT_MERCHANT + " WHERE id = :id")
                .param("id", id)
                .query(JdbcMerchantRepository::mapRow)
                .optional();
    }

    @Override
    public Optional<Merchant> findByOwner(UUID ownerUserId) {
        return jdbc.sql(SELECT_MERCHANT + " WHERE owner_user_id = :owner")
                .param("owner", ownerUserId)
                .query(JdbcMerchantRepository::mapRow)
                .optional();
    }

    @Override
    public Optional<Merchant> findByMember(UUID userId) {
        return jdbc.sql(SELECT_MERCHANT + """
                WHERE id = (
                    SELECT merchant_id FROM merchant.merchant_memberships
                    WHERE user_id = :userId AND status = 'ACTIVE'
                )
                """)
                .param("userId", userId)
                .query(JdbcMerchantRepository::mapRow)
                .optional();
    }

    @Override
    public void lockForOfferMutation(UUID merchantId) {
        jdbc.sql("SELECT id FROM merchant.merchants WHERE id = :id FOR UPDATE")
                .param("id", merchantId).query(UUID.class).optional();
    }

    @Override
    public boolean updateIfStatus(Merchant m, MerchantStatus expectedStatus) {
        int updated = jdbc.sql("""
                UPDATE merchant.merchants
                SET name = :name, business_type = :type, address = :address, city = :city, district = :district,
                    latitude = COALESCE(:latitude, latitude), longitude = COALESCE(:longitude, longitude),
                    phone = :phone, opening_hours = :hours, pickup_instructions = :pickupInstructions, status = :status,
                    decision_reason = :reason, decided_by = :decidedBy, decided_at = :decidedAt,
                    updated_at = :updatedAt
                WHERE id = :id AND status = :expected
                """)
                .param("name", m.name())
                .param("type", m.businessType().name())
                .param("address", m.address())
                .param("city", m.city())
                .param("district", m.district(), Types.VARCHAR)
                .param("latitude", m.latitude(), Types.DOUBLE)
                .param("longitude", m.longitude(), Types.DOUBLE)
                .param("phone", m.phone())
                .param("hours", m.openingHours())
                .param("pickupInstructions", m.pickupInstructions(), Types.VARCHAR)
                .param("status", m.status().name())
                .param("reason", m.decisionReason(), Types.VARCHAR)
                .param("decidedBy", m.decidedBy(), Types.OTHER)
                .param("decidedAt", toDb(m.decidedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("updatedAt", toDb(m.updatedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", m.id())
                .param("expected", expectedStatus.name())
                .update();
        return updated == 1;
    }

    @Override
    public List<Merchant> list(MerchantStatus statusOrNull, int limit, int offset) {
        String where = statusOrNull == null ? "" : " WHERE status = :status";
        JdbcClient.StatementSpec spec = jdbc.sql(
                        SELECT_MERCHANT + where + " ORDER BY created_at ASC, id ASC LIMIT :limit OFFSET :offset")
                .param("limit", limit)
                .param("offset", offset);
        if (statusOrNull != null) {
            spec = spec.param("status", statusOrNull.name());
        }
        return spec.query(JdbcMerchantRepository::mapRow).list();
    }

    @Override
    public long count(MerchantStatus statusOrNull) {
        String where = statusOrNull == null ? "" : " WHERE status = :status";
        JdbcClient.StatementSpec spec = jdbc.sql("SELECT count(*) FROM merchant.merchants" + where);
        if (statusOrNull != null) {
            spec = spec.param("status", statusOrNull.name());
        }
        return spec.query(Long.class).single();
    }

    @Override
    public List<UUID> findApprovedIds(String city, String district) {
        StringBuilder sql = new StringBuilder("SELECT id FROM merchant.merchants WHERE status='APPROVED'");
        if (city != null) sql.append(" AND lower(city)=lower(:city)");
        if (district != null) sql.append(" AND district IS NOT NULL AND lower(district)=lower(:district)");
        sql.append(" ORDER BY id");
        JdbcClient.StatementSpec query = jdbc.sql(sql.toString());
        if (city != null) query = query.param("city", city);
        if (district != null) query = query.param("district", district);
        return query.query(UUID.class).list();
    }

    @Override
    public List<UUID> findApprovedIdsByName(String query, String city, String district) {
        StringBuilder sql = new StringBuilder("SELECT id FROM merchant.merchants WHERE status='APPROVED' AND POSITION(lower(:query) IN lower(name)) > 0");
        if (city != null) sql.append(" AND lower(city)=lower(:city)");
        if (district != null) sql.append(" AND district IS NOT NULL AND lower(district)=lower(:district)");
        sql.append(" ORDER BY id");
        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString()).param("query", query);
        if (city != null) statement = statement.param("city", city);
        if (district != null) statement = statement.param("district", district);
        return statement.query(UUID.class).list();
    }

    @Override
    public List<Merchant> findApprovedByIds(java.util.Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return jdbc.sql(SELECT_MERCHANT + " WHERE status='APPROVED' AND id IN (:ids) ORDER BY id")
                .param("ids", ids).query(JdbcMerchantRepository::mapRow).list();
    }

    private static Merchant mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Merchant(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_user_id", UUID.class),
                rs.getString("name"),
                BusinessType.valueOf(rs.getString("business_type")),
                rs.getString("address"),
                rs.getString("city"),
                rs.getString("district"),
                (Double) rs.getObject("latitude"),
                (Double) rs.getObject("longitude"),
                rs.getString("phone"),
                rs.getString("opening_hours"),
                rs.getString("pickup_instructions"),
                MerchantStatus.valueOf(rs.getString("status")),
                rs.getString("decision_reason"),
                rs.getObject("decided_by", UUID.class),
                toInstant(rs.getObject("decided_at", OffsetDateTime.class)),
                toInstant(rs.getObject("created_at", OffsetDateTime.class)),
                toInstant(rs.getObject("updated_at", OffsetDateTime.class)));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** PostgreSQL's JDBC driver does not bind java.time.Instant, so convert to OffsetDateTime. */
    private static OffsetDateTime toDb(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
