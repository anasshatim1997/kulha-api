package ma.kulha.offer.application;

import java.util.List;
import java.util.UUID;
import ma.kulha.offer.domain.model.OfferStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminOfferModerationService {
    private final JdbcClient jdbc;
    AdminOfferModerationService(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public OfferPage list(String status, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.clamp(size, 1, 100);
        OfferStatus filter = status == null || status.isBlank() ? null : OfferStatus.valueOf(status.toUpperCase(java.util.Locale.ROOT));
        String clause = filter == null ? "" : " AND o.status=:status";
        JdbcClient.StatementSpec query = jdbc.sql("""
                SELECT o.id, o.title, m.name AS shop_name, m.city, o.price_minor, o.original_price_minor,
                       o.quantity_total, o.quantity_reserved, o.allergens_status,
                       array_to_string(o.allergens, ',') AS allergens, o.food_notes,
                       o.status, o.pickup_start, o.pickup_end, o.image_url
                FROM offer.offers o JOIN merchant.merchants m ON m.id=o.merchant_id
                WHERE TRUE
                """ + clause + " ORDER BY CASE WHEN o.status='SUSPENDED' THEN 0 WHEN o.status='UNPUBLISHED' THEN 1 ELSE 2 END, o.updated_at DESC, o.id LIMIT :limit OFFSET :offset")
                .param("limit", safeSize).param("offset", safePage * safeSize);
        if (filter != null) query=query.param("status", filter.name());
        List<OfferRow> rows = query.query((rs,row) -> new OfferRow(
                rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("shop_name"),
                rs.getString("city"), rs.getInt("price_minor"), (Integer) rs.getObject("original_price_minor"),
                rs.getInt("quantity_total"), rs.getInt("quantity_reserved"), rs.getString("allergens_status"),
                rs.getString("allergens"), rs.getString("food_notes"), rs.getString("status"),
                instant(rs.getObject("pickup_start", java.time.OffsetDateTime.class)),
                instant(rs.getObject("pickup_end", java.time.OffsetDateTime.class)), rs.getString("image_url")
        )).list();
        JdbcClient.StatementSpec count = jdbc.sql("SELECT count(*) FROM offer.offers o WHERE TRUE" + clause);
        if (filter != null) count=count.param("status", filter.name());
        return new OfferPage(rows, safePage, safeSize, count.query(Long.class).single());
    }

    @Transactional(readOnly = true)
    public OfferRow get(UUID id) {
        return jdbc.sql("""
                SELECT o.id, o.title, m.name AS shop_name, m.city, o.price_minor, o.original_price_minor,
                       o.quantity_total, o.quantity_reserved, o.allergens_status,
                       array_to_string(o.allergens, ',') AS allergens, o.food_notes,
                       o.status, o.pickup_start, o.pickup_end, o.image_url
                FROM offer.offers o JOIN merchant.merchants m ON m.id=o.merchant_id WHERE o.id=:id
                """).param("id", id).query((rs,row) -> new OfferRow(
                rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("shop_name"), rs.getString("city"),
                rs.getInt("price_minor"), (Integer) rs.getObject("original_price_minor"), rs.getInt("quantity_total"),
                rs.getInt("quantity_reserved"), rs.getString("allergens_status"), rs.getString("allergens"),
                rs.getString("food_notes"), rs.getString("status"),
                instant(rs.getObject("pickup_start", java.time.OffsetDateTime.class)),
                instant(rs.getObject("pickup_end", java.time.OffsetDateTime.class)), rs.getString("image_url")
        )).optional().orElseThrow(OfferNotFoundException::new);
    }

    private static java.time.Instant instant(java.time.OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
    public record OfferRow(UUID id, String title, String shopName, String city, int priceMinor,
                           Integer originalPriceMinor, int quantityTotal, int quantityReserved,
                           String allergensStatus, String allergens, String foodNotes, String status,
                           java.time.Instant pickupStart, java.time.Instant pickupEnd, String imageUrl) {}
    public record OfferPage(List<OfferRow> items, int page, int size, long total) {}
}
