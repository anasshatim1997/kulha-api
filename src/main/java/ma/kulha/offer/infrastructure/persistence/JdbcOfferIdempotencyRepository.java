package ma.kulha.offer.infrastructure.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.kulha.offer.domain.model.AllergensStatus;
import ma.kulha.offer.domain.model.Offer;
import ma.kulha.offer.domain.model.OfferCategory;
import ma.kulha.offer.domain.model.OfferStatus;
import ma.kulha.offer.domain.repository.OfferIdempotencyRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOfferIdempotencyRepository implements OfferIdempotencyRepository {
    private final JdbcClient jdbc;

    JdbcOfferIdempotencyRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public void lock(UUID actorUserId, String key) {
        String lockName = actorUserId + ":" + key;
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:lockName, 0))")
                .param("lockName", lockName).query((rs, row) -> Boolean.TRUE).single();
    }

    @Override
    public Optional<IdempotencyRecord> find(UUID actorUserId, String key) {
        return jdbc.sql("""
                SELECT i.request_hash, s.id, s.merchant_id, s.title, s.description, s.category,
                       s.price_minor, s.original_price_minor, s.quantity_total, s.quantity_reserved, s.reservation_started_at,
                       s.pickup_start, s.pickup_end, s.allergens_status, s.allergens, s.food_notes,
                       s.status, s.published_at, s.version, s.created_at, s.updated_at
                FROM offer.idempotency_keys i
                CROSS JOIN LATERAL jsonb_populate_record(NULL::offer.offers, i.response_snapshot) AS s
                WHERE i.actor_user_id=:actor AND i.idempotency_key=:key
                """)
                .param("actor", actorUserId).param("key", key)
                .query((rs, row) -> new OfferIdempotencyRepository.IdempotencyRecord(rs.getString("request_hash"), mapOffer(rs)))
                .optional();
    }

    @Override
    public void save(UUID actorUserId, String key, String requestHash, UUID offerId) {
        jdbc.sql("""
                INSERT INTO offer.idempotency_keys (actor_user_id, idempotency_key, request_hash, response_snapshot)
                SELECT :actor, :key, :hash, to_jsonb(o) FROM offer.offers o WHERE o.id=:offerId
                """)
                .param("actor", actorUserId).param("key", key).param("hash", requestHash).param("offerId", offerId).update();
    }

    private static Offer mapOffer(ResultSet rs) throws SQLException {
        Array array = rs.getArray("allergens");
        List<String> allergens = array == null ? List.of() : Arrays.asList((String[]) array.getArray());
        return new Offer(rs.getObject("id", UUID.class), rs.getObject("merchant_id", UUID.class),
                rs.getString("title"), rs.getString("description"), OfferCategory.valueOf(rs.getString("category")),
                rs.getInt("price_minor"), (Integer) rs.getObject("original_price_minor"), rs.getInt("quantity_total"),
                rs.getInt("quantity_reserved"), toInstant(rs.getObject("reservation_started_at", OffsetDateTime.class)),
                toInstant(rs.getObject("pickup_start", OffsetDateTime.class)),
                toInstant(rs.getObject("pickup_end", OffsetDateTime.class)), AllergensStatus.valueOf(rs.getString("allergens_status")),
                allergens, rs.getString("food_notes"), OfferStatus.valueOf(rs.getString("status")),
                toInstant(rs.getObject("published_at", OffsetDateTime.class)), rs.getLong("version"),
                toInstant(rs.getObject("created_at", OffsetDateTime.class)), toInstant(rs.getObject("updated_at", OffsetDateTime.class)));
    }

    private static java.time.Instant toInstant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
}
