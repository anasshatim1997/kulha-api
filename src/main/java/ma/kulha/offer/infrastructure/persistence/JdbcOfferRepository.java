package ma.kulha.offer.infrastructure.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.kulha.offer.domain.model.AllergensStatus;
import ma.kulha.offer.domain.model.Offer;
import ma.kulha.offer.domain.model.OfferCategory;
import ma.kulha.offer.domain.model.OfferStatus;
import ma.kulha.offer.domain.model.OfferSummary;
import ma.kulha.offer.domain.repository.OfferRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOfferRepository implements OfferRepository {

    private static final String SELECT = """
            SELECT id, merchant_id, title, description, category, price_minor, original_price_minor,
                   quantity_total, quantity_reserved, reservation_started_at, pickup_start, pickup_end, allergens_status,
                   allergens, food_notes, status, published_at, version, created_at, updated_at
            FROM offer.offers
            """;

    private final JdbcClient jdbc;
    private final int reservationCutoffMinutes;

    JdbcOfferRepository(JdbcClient jdbc,
                        @org.springframework.beans.factory.annotation.Value("${app.offer.reservation-cutoff-minutes:30}") int reservationCutoffMinutes) {
        this.jdbc = jdbc;
        if (reservationCutoffMinutes < 0) throw new IllegalArgumentException("app.offer.reservation-cutoff-minutes cannot be negative");
        this.reservationCutoffMinutes = reservationCutoffMinutes;
    }

    @Override
    public void insert(Offer o) {
        jdbc.sql("""
                INSERT INTO offer.offers (id, merchant_id, title, description, category, price_minor,
                    original_price_minor, quantity_total, quantity_reserved, reservation_started_at, pickup_start, pickup_end,
                    allergens_status, allergens, food_notes, status, published_at, version, created_at, updated_at)
                VALUES (:id, :merchant, :title, :description, :category, :price, :originalPrice,
                    :total, :reserved, :reservationStartedAt, :pickupStart, :pickupEnd, :allergensStatus, CAST(:allergens AS varchar[]),
                    :foodNotes, :status, :publishedAt, :version, :createdAt, :updatedAt)
                """)
                .param("id", o.id()).param("merchant", o.merchantId()).param("title", o.title())
                .param("description", o.description()).param("category", o.category().name())
                .param("price", o.priceMinor()).param("originalPrice", o.originalPriceMinor(), Types.INTEGER)
                .param("total", o.quantityTotal()).param("reserved", o.quantityReserved())
                .param("reservationStartedAt", toDb(o.reservationStartedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("pickupStart", toDb(o.pickupStart()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("pickupEnd", toDb(o.pickupEnd()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("allergensStatus", o.allergensStatus().name())
                .param("allergens", arrayLiteral(o.allergens()))
                .param("foodNotes", o.foodNotes(), Types.VARCHAR).param("status", o.status().name())
                .param("publishedAt", toDb(o.publishedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("version", o.version()).param("createdAt", toDb(o.createdAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("updatedAt", toDb(o.updatedAt()), Types.TIMESTAMP_WITH_TIMEZONE).update();
    }

    @Override
    public Optional<Offer> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", id)
                .query(JdbcOfferRepository::mapOffer).optional();
    }

    @Override
    public Optional<Offer> findByIdForUpdate(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id FOR UPDATE").param("id", id)
                .query(JdbcOfferRepository::mapOffer).optional();
    }

    @Override
    public List<OfferSummary> listByMerchant(UUID merchantId, OfferStatus status, int limit, int offset) {
        String filter = status == null ? "" : " AND status = :status";
        JdbcClient.StatementSpec query = jdbc.sql("""
                SELECT id, title, category, price_minor, quantity_total, quantity_reserved,
                       pickup_start, pickup_end, status, created_at, version
                FROM offer.offers WHERE merchant_id = :merchant
                """ + filter + " ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset")
                .param("merchant", merchantId).param("limit", limit).param("offset", offset);
        if (status != null) query = query.param("status", status.name());
        return query.query((rs, row) -> new OfferSummary(
                rs.getObject("id", UUID.class), rs.getString("title"), OfferCategory.valueOf(rs.getString("category")),
                rs.getInt("price_minor"), rs.getInt("quantity_total"), rs.getInt("quantity_reserved"),
                toInstant(rs.getObject("pickup_start", OffsetDateTime.class)),
                toInstant(rs.getObject("pickup_end", OffsetDateTime.class)), OfferStatus.valueOf(rs.getString("status")),
                toInstant(rs.getObject("created_at", OffsetDateTime.class)), rs.getLong("version"))).list();
    }

    @Override
    public long countByMerchant(UUID merchantId, OfferStatus status) {
        String filter = status == null ? "" : " AND status = :status";
        JdbcClient.StatementSpec query = jdbc.sql("SELECT count(*) FROM offer.offers WHERE merchant_id = :merchant" + filter)
                .param("merchant", merchantId);
        if (status != null) query = query.param("status", status.name());
        return query.query(Long.class).single();
    }

    @Override
    public long countActiveByMerchant(UUID merchantId) {
        return jdbc.sql("SELECT count(*) FROM offer.offers WHERE merchant_id=:merchant AND status IN ('DRAFT','PUBLISHED')")
                .param("merchant", merchantId).query(Long.class).single();
    }

    @Override
    public boolean update(Offer o, long expectedVersion) {
        return jdbc.sql("""
                UPDATE offer.offers SET title=:title, description=:description, category=:category,
                    price_minor=:price, original_price_minor=:originalPrice, quantity_total=:total,
                    pickup_start=:pickupStart, pickup_end=:pickupEnd, allergens_status=:allergensStatus,
                    allergens=CAST(:allergens AS varchar[]), food_notes=:foodNotes, status=:status,
                    published_at=:publishedAt, version=:version, updated_at=:updatedAt
                WHERE id=:id AND version=:expected AND quantity_reserved=:expectedReserved
                  AND reservation_started_at IS NOT DISTINCT FROM :expectedReservationStartedAt
                """)
                .param("title", o.title()).param("description", o.description()).param("category", o.category().name())
                .param("price", o.priceMinor()).param("originalPrice", o.originalPriceMinor(), Types.INTEGER)
                .param("total", o.quantityTotal()).param("pickupStart", toDb(o.pickupStart()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("pickupEnd", toDb(o.pickupEnd()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("allergensStatus", o.allergensStatus().name()).param("allergens", arrayLiteral(o.allergens()))
                .param("foodNotes", o.foodNotes(), Types.VARCHAR).param("status", o.status().name())
                .param("publishedAt", toDb(o.publishedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("version", o.version()).param("updatedAt", toDb(o.updatedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", o.id()).param("expected", expectedVersion)
                .param("expectedReserved", o.quantityReserved())
                .param("expectedReservationStartedAt", toDb(o.reservationStartedAt()), Types.TIMESTAMP_WITH_TIMEZONE).update() == 1;
    }

    @Override
    public boolean tryHoldUnits(UUID offerId, int units) {
        if (units < 1) throw new IllegalArgumentException("units must be positive");
        return jdbc.sql("""
                UPDATE offer.offers SET quantity_reserved = quantity_reserved + :units,
                    reservation_started_at = COALESCE(reservation_started_at, now()), updated_at = now()
                WHERE id = :id AND status = 'PUBLISHED'
                  AND pickup_end > now() + (:cutoff * interval '1 minute')
                  AND quantity_total - quantity_reserved >= :units
                """).param("units", units).param("cutoff", reservationCutoffMinutes).param("id", offerId).update() == 1;
    }

    @Override
    public boolean releaseUnits(UUID offerId, int units) {
        if (units < 1) throw new IllegalArgumentException("units must be positive");
        return jdbc.sql("""
                UPDATE offer.offers SET quantity_reserved = quantity_reserved - :units, updated_at = now()
                WHERE id = :id AND quantity_reserved >= :units
                """).param("units", units).param("id", offerId).update() == 1;
    }

    @Override
    public List<Offer> findPublishedByMerchant(UUID merchantId) {
        return jdbc.sql(SELECT + " WHERE merchant_id=:merchant AND status='PUBLISHED' ORDER BY id")
                .param("merchant", merchantId).query(JdbcOfferRepository::mapOffer).list();
    }

    @Override
    public List<Offer> expireBatch(Instant now, int limit) {
        return jdbc.sql("""
                WITH expired AS (
                    SELECT id FROM offer.offers
                    WHERE status IN ('PUBLISHED','UNPUBLISHED') AND pickup_end IS NOT NULL AND pickup_end < :now
                    ORDER BY pickup_end, id LIMIT :limit FOR UPDATE SKIP LOCKED
                ), updated AS (
                    UPDATE offer.offers o SET status='EXPIRED', version=o.version+1, updated_at=:now
                    FROM expired WHERE o.id=expired.id
                    RETURNING o.*
                )
                SELECT id, merchant_id, title, description, category, price_minor, original_price_minor,
                       quantity_total, quantity_reserved, reservation_started_at, pickup_start, pickup_end, allergens_status,
                       allergens, food_notes, status, published_at, version, created_at, updated_at
                FROM updated ORDER BY pickup_end, id
                """).param("now", toDb(now), Types.TIMESTAMP_WITH_TIMEZONE).param("limit", limit)
                .query(JdbcOfferRepository::mapOffer).list();
    }

    private static Offer mapOffer(ResultSet rs, int row) throws SQLException {
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

    private static String arrayLiteral(List<String> values) {
        return "{" + String.join(",", values) + "}";
    }
    private static Instant toInstant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
    private static OffsetDateTime toDb(Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
}
