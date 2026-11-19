package ma.kulha.reservation.infrastructure.persistence;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.kulha.reservation.domain.model.Reservation;
import ma.kulha.reservation.domain.model.ReservationFilter;
import ma.kulha.reservation.domain.model.ReservationStatus;
import ma.kulha.reservation.domain.repository.ReservationRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
@Repository
class JdbcReservationRepository implements ReservationRepository {
    private static final String SELECT = """
            SELECT id, reference, customer_id, offer_id, merchant_id, offer_title, offer_category,
                   merchant_name, merchant_address, merchant_city, merchant_pickup_instructions,
                   pickup_start, pickup_end, pickup_code, quantity, unit_price_minor, total_price_minor,
                   status, created_at, updated_at
            FROM reservation.reservations
            """;
    private final JdbcClient jdbc;
    JdbcReservationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    @Override public void insert(Reservation r) {
        jdbc.sql("""
                INSERT INTO reservation.reservations
                    (id, reference, customer_id, offer_id, merchant_id, offer_title, offer_category,
                     merchant_name, merchant_address, merchant_city, merchant_pickup_instructions,
                     pickup_start, pickup_end, pickup_code, quantity, unit_price_minor, total_price_minor,
                     status, created_at, updated_at)
                VALUES
                    (:id, :reference, :customer, :offer, :merchant, :offerTitle, :offerCategory,
                     :merchantName, :merchantAddress, :merchantCity, :merchantInstructions,
                     :pickupStart, :pickupEnd, :pickupCode, :quantity, :unitPrice, :totalPrice,
                     :status, :createdAt, :updatedAt)
                """)
                .param("id", r.id()).param("reference", r.reference()).param("customer", r.customerId())
                .param("offer", r.offerId()).param("merchant", r.merchantId()).param("offerTitle", r.offerTitle())
                .param("offerCategory", r.offerCategory()).param("merchantName", r.merchantName())
                .param("merchantAddress", r.merchantAddress()).param("merchantCity", r.merchantCity())
                .param("merchantInstructions", r.merchantPickupInstructions(), Types.VARCHAR)
                .param("pickupStart", toDb(r.pickupStart()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("pickupEnd", toDb(r.pickupEnd()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("pickupCode", r.pickupCode()).param("quantity", r.quantity())
                .param("unitPrice", r.unitPriceMinor()).param("totalPrice", r.totalPriceMinor())
                .param("status", r.status().name()).param("createdAt", toDb(r.createdAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("updatedAt", toDb(r.updatedAt()), Types.TIMESTAMP_WITH_TIMEZONE).update();
    }
    @Override public Optional<Reservation> findByReference(String reference) {
        return jdbc.sql(SELECT + " WHERE reference = :reference").param("reference", reference)
                .query(JdbcReservationRepository::map).optional();
    }
    @Override public Optional<Reservation> findByReferenceForUpdate(String reference) {
        return jdbc.sql(SELECT + " WHERE reference = :reference FOR UPDATE").param("reference", reference)
                .query(JdbcReservationRepository::map).optional();
    }
    @Override public Optional<Reservation> findByPickupCredentialHashForUpdate(String hash) {
        return jdbc.sql(SELECT + " WHERE pickup_credential_hash = :hash FOR UPDATE")
                .param("hash", hash).query(JdbcReservationRepository::map).optional();
    }

    @Override public boolean savePickupCredential(UUID reservationId, String hash, Instant updatedAt) {
        return jdbc.sql("""
                UPDATE reservation.reservations
                   SET pickup_credential_hash = :hash, updated_at = :updatedAt
                 WHERE id = :id AND status = 'CONFIRMED' AND pickup_end > :updatedAt
                """)
                .param("hash", hash).param("updatedAt", toDb(updatedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", reservationId).update() == 1;
    }

    @Override public boolean updateStatus(Reservation r, ReservationStatus expectedStatus) {
        return jdbc.sql("""
                UPDATE reservation.reservations
                   SET status = :status, updated_at = :updatedAt, pickup_credential_hash = NULL
                 WHERE id = :id AND status = :expectedStatus
                """)
                .param("status", r.status().name()).param("updatedAt", toDb(r.updatedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("id", r.id()).param("expectedStatus", expectedStatus.name()).update() == 1;
    }
    @Override public void recordCancellation(Reservation r, String reasonCode, String comment, Instant cancelledAt) {
        long minutesBeforePickup = Math.max(0, java.time.Duration.between(cancelledAt, r.pickupStart()).toMinutes());
        jdbc.sql("""
                INSERT INTO reservation.cancellation_events
                    (id, reservation_id, customer_id, merchant_id, offer_id, offer_category,
                     reason_code, comment, actor_type, pickup_start, minutes_before_pickup,
                     quantity, total_price_minor, cancelled_at)
                VALUES
                    (:id, :reservation, :customer, :merchant, :offer, :category,
                     :reason, :comment, 'CUSTOMER', :pickupStart, :minutesBeforePickup,
                     :quantity, :totalPrice, :cancelledAt)
                """)
                .param("id", UUID.randomUUID()).param("reservation", r.id()).param("customer", r.customerId())
                .param("merchant", r.merchantId()).param("offer", r.offerId()).param("category", r.offerCategory())
                .param("reason", reasonCode).param("comment", comment, Types.VARCHAR)
                .param("pickupStart", toDb(r.pickupStart()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("minutesBeforePickup", minutesBeforePickup).param("quantity", r.quantity())
                .param("totalPrice", r.totalPriceMinor())
                .param("cancelledAt", toDb(cancelledAt), Types.TIMESTAMP_WITH_TIMEZONE).update();
    }

    @Override public List<Reservation> list(UUID customerId, ReservationFilter filter, Instant now, int limit, int offset) {
        String order = filter == ReservationFilter.UPCOMING
                ? "pickup_start ASC, created_at ASC" : "pickup_start DESC, created_at DESC";
        return jdbc.sql(SELECT + " WHERE customer_id = :customer AND " + filterSql(filter)
                        + " ORDER BY " + order + " LIMIT :limit OFFSET :offset")
                .param("customer", customerId).param("now", toDb(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("limit", limit).param("offset", offset).query(JdbcReservationRepository::map).list();
    }
    @Override public long count(UUID customerId, ReservationFilter filter, Instant now) {
        return jdbc.sql("SELECT count(*) FROM reservation.reservations WHERE customer_id = :customer AND " + filterSql(filter))
                .param("customer", customerId).param("now", toDb(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .query(Long.class).single();
    }
    @Override public int expireActive(Instant now) {
        return jdbc.sql("""
                UPDATE reservation.reservations SET status = 'EXPIRED', updated_at = :now, pickup_credential_hash = NULL
                WHERE status IN ('PENDING','CONFIRMED') AND pickup_end <= :now
                """).param("now", toDb(now), Types.TIMESTAMP_WITH_TIMEZONE).update();
    }
    private static String filterSql(ReservationFilter filter) {
        return switch (filter) {
            case UPCOMING -> "status IN ('PENDING','CONFIRMED') AND pickup_end > :now";
            case PAST -> "(status = 'COMPLETED' OR (status IN ('PENDING','CONFIRMED') AND pickup_end <= :now))";
            case CANCELLED -> "status IN ('CANCELLED','EXPIRED')";
            case ALL -> "TRUE";
        };
    }
    private static Reservation map(ResultSet rs, int row) throws SQLException {
        return new Reservation(rs.getObject("id", UUID.class), rs.getString("reference"),
                rs.getObject("customer_id", UUID.class), rs.getObject("offer_id", UUID.class),
                rs.getObject("merchant_id", UUID.class), rs.getString("offer_title"), rs.getString("offer_category"),
                rs.getString("merchant_name"), rs.getString("merchant_address"), rs.getString("merchant_city"),
                rs.getString("merchant_pickup_instructions"),
                toInstant(rs.getObject("pickup_start", OffsetDateTime.class)),
                toInstant(rs.getObject("pickup_end", OffsetDateTime.class)), rs.getString("pickup_code"),
                rs.getInt("quantity"), rs.getInt("unit_price_minor"), rs.getInt("total_price_minor"),
                ReservationStatus.valueOf(rs.getString("status")),
                toInstant(rs.getObject("created_at", OffsetDateTime.class)),
                toInstant(rs.getObject("updated_at", OffsetDateTime.class)));
    }
    private static Instant toInstant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
    private static OffsetDateTime toDb(Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
}
