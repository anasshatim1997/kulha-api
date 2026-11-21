package ma.kulha.merchant.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import ma.kulha.admin.application.AuditApi;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantOperationsService {
    private static final ZoneId SHOP_ZONE = ZoneId.of("Africa/Casablanca");
    private final JdbcClient jdbc;
    private final MerchantApi merchants;
    private final AuditApi audit;
    private final Clock clock;

    MerchantOperationsService(JdbcClient jdbc, MerchantApi merchants, AuditApi audit, Clock clock) {
        this.jdbc = jdbc;
        this.merchants = merchants;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(UUID userId) {
        UUID merchantId = requirePermission(userId, "VIEW_ORDERS");
        LocalDate today = LocalDate.now(clock.withZone(SHOP_ZONE));
        Stats stats = jdbc.sql("""
                SELECT
                  (SELECT count(*) FROM offer.offers WHERE merchant_id=:merchant AND status='PUBLISHED') AS live_offers,
                  (SELECT count(*) FROM reservation.reservations WHERE merchant_id=:merchant
                    AND (created_at AT TIME ZONE 'Africa/Casablanca')::date=:today) AS reserved_today,
                  (SELECT coalesce(sum(quantity),0) FROM reservation.reservations WHERE merchant_id=:merchant
                    AND (created_at AT TIME ZONE 'Africa/Casablanca')::date=:today) AS baskets_today,
                  (SELECT count(*) FROM reservation.reservations WHERE merchant_id=:merchant AND status='CONFIRMED'
                    AND pickup_start <= now() AND pickup_end > now()) AS to_collect_now,
                  (SELECT count(*) FROM reservation.reservations WHERE merchant_id=:merchant AND status='COMPLETED'
                    AND (updated_at AT TIME ZONE 'Africa/Casablanca')::date=:today) AS collected_today
                """).param("merchant", merchantId).param("today", today)
                .query((rs, row) -> new Stats(rs.getLong("live_offers"), rs.getLong("reserved_today"),
                        rs.getLong("baskets_today"), rs.getLong("to_collect_now"), rs.getLong("collected_today")))
                .single();

        Shop shop = jdbc.sql("SELECT name, city FROM merchant.merchants WHERE id=:merchant")
                .param("merchant", merchantId)
                .query((rs, row) -> new Shop(rs.getString("name"), rs.getString("city"))).single();

        List<LiveOffer> offers = jdbc.sql("""
                SELECT id, title, price_minor, quantity_total, quantity_reserved, status
                FROM offer.offers WHERE merchant_id=:merchant AND status IN ('PUBLISHED','DRAFT','UNPUBLISHED')
                ORDER BY updated_at DESC, id LIMIT 8
                """).param("merchant", merchantId)
                .query((rs, row) -> new LiveOffer(rs.getObject("id", UUID.class), rs.getString("title"),
                        rs.getInt("price_minor"), rs.getInt("quantity_total"), rs.getInt("quantity_reserved"),
                        rs.getString("status"))).list();

        return new Dashboard(shop.name(), shop.city(), stats.liveOffers(), stats.reservedToday(),
                stats.basketsToday(), stats.toCollectNow(), stats.collectedToday(),
                ordersFor(merchantId, "TODAY", 8), offers);
    }

    @Transactional(readOnly = true)
    public List<OrderRow> orders(UUID userId, String filter) {
        UUID merchantId = requirePermission(userId, "VIEW_ORDERS");
        return ordersFor(merchantId, filter == null ? "ALL" : filter, 100);
    }

    @Transactional(readOnly = true)
    public Sales sales(UUID ownerId, LocalDate from, LocalDate to) {
        UUID merchantId = merchants.findMerchantIdByOwner(ownerId)
                .orElseThrow(() -> new AccessDeniedException("Merchant owner access required."));
        if (!merchants.isApproved(merchantId)) throw new AccessDeniedException("Approved merchant access required.");
        LocalDate end = to == null ? LocalDate.now(clock.withZone(SHOP_ZONE)) : to;
        LocalDate start = from == null ? end.minusDays(6) : from;
        validateRange(start, end);

        Totals totals = jdbc.sql("""
                SELECT
                  coalesce(sum(quantity) FILTER (WHERE status='COMPLETED'),0) AS baskets_collected,
                  coalesce(sum(total_price_minor) FILTER (WHERE status='COMPLETED'),0) AS sales_minor,
                  count(*) FILTER (WHERE status='CANCELLED') AS cancelled,
                  count(*) FILTER (WHERE status='EXPIRED') AS not_collected
                FROM reservation.reservations
                WHERE merchant_id=:merchant
                  AND (updated_at AT TIME ZONE 'Africa/Casablanca')::date BETWEEN :fromDate AND :toDate
                """).param("merchant", merchantId).param("fromDate", start).param("toDate", end)
                .query((rs, row) -> new Totals(rs.getLong("baskets_collected"), rs.getLong("sales_minor"),
                        rs.getLong("cancelled"), rs.getLong("not_collected"))).single();

        List<DailySale> daily = jdbc.sql("""
                SELECT days.day::date AS sale_day,
                       coalesce(sum(r.total_price_minor),0) AS amount_minor
                FROM generate_series(CAST(:fromDate AS date), CAST(:toDate AS date), interval '1 day') days(day)
                LEFT JOIN reservation.reservations r ON r.merchant_id=:merchant
                  AND r.status='COMPLETED'
                  AND (r.updated_at AT TIME ZONE 'Africa/Casablanca')::date=days.day::date
                GROUP BY days.day ORDER BY days.day
                """).param("fromDate", start).param("toDate", end).param("merchant", merchantId)
                .query((rs, row) -> new DailySale(rs.getObject("sale_day", LocalDate.class),
                        rs.getLong("amount_minor"))).list();

        return new Sales(totals.basketsCollected(), totals.salesMinor(), totals.cancelled(),
                totals.notCollected(), start, end, daily, ordersForRange(merchantId, start, end, 10));
    }

    @Transactional
    public String exportSales(UUID ownerId, LocalDate from, LocalDate to) {
        Sales sales = sales(ownerId, from, to);
        UUID merchantId = merchants.findMerchantIdByOwner(ownerId).orElseThrow();
        audit.append(ownerId, "MERCHANT_SALES_EXPORTED", "MERCHANT", merchantId.toString(),
                sales.from() + " to " + sales.to(), "SUCCESS");
        StringBuilder csv = new StringBuilder("date,reference,basket,quantity,amount_mad,status\n");
        for (OrderRow row : ordersForRange(merchantId, sales.from(), sales.to(), 500)) {
            csv.append(row.createdAt().atZone(SHOP_ZONE).toLocalDate()).append(',')
                    .append(csv(row.reference())).append(',').append(csv(row.basket()))
                    .append(',').append(row.quantity()).append(',')
                    .append(String.format(java.util.Locale.ROOT, "%.2f", row.amountMinor() / 100.0)).append(',')
                    .append(csv(row.status())).append('\n');
        }
        return csv.toString();
    }

    private List<OrderRow> ordersFor(UUID merchantId, String filter, int limit) {
        String where = switch (filter) {
            case "TO_COLLECT" -> " AND r.status='CONFIRMED'";
            case "DONE" -> " AND r.status IN ('COMPLETED','CANCELLED','EXPIRED')";
            case "TODAY" -> " AND (r.created_at AT TIME ZONE 'Africa/Casablanca')::date = (now() AT TIME ZONE 'Africa/Casablanca')::date";
            case "ALL" -> "";
            default -> throw new IllegalArgumentException("Unsupported order filter.");
        };
        return jdbc.sql("""
                SELECT r.reference, r.offer_title, r.quantity, r.pickup_start, r.pickup_end,
                       r.total_price_minor, r.status, r.created_at, u.full_name
                FROM reservation.reservations r
                JOIN identity.users u ON u.id=r.customer_id
                WHERE r.merchant_id=:merchant
                """ + where + " ORDER BY r.pickup_start DESC, r.created_at DESC LIMIT :limit")
                .param("merchant", merchantId).param("limit", limit)
                .query((rs, row) -> new OrderRow(rs.getString("reference"), customerInitials(rs.getString("full_name")),
                        rs.getString("offer_title"), rs.getInt("quantity"),
                        rs.getObject("pickup_start", java.time.OffsetDateTime.class).toInstant(),
                        rs.getObject("pickup_end", java.time.OffsetDateTime.class).toInstant(),
                        rs.getLong("total_price_minor"), rs.getString("status"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant())).list();
    }

    private List<OrderRow> ordersForRange(UUID merchantId, LocalDate from, LocalDate to, int limit) {
        return jdbc.sql("""
                SELECT r.reference, r.offer_title, r.quantity, r.pickup_start, r.pickup_end,
                       r.total_price_minor, r.status, r.created_at, u.full_name
                FROM reservation.reservations r
                JOIN identity.users u ON u.id=r.customer_id
                WHERE r.merchant_id=:merchant
                  AND (r.created_at AT TIME ZONE 'Africa/Casablanca')::date BETWEEN :fromDate AND :toDate
                ORDER BY r.created_at DESC LIMIT :limit
                """).param("merchant", merchantId).param("fromDate", from).param("toDate", to).param("limit", limit)
                .query((rs, row) -> new OrderRow(rs.getString("reference"), customerInitials(rs.getString("full_name")),
                        rs.getString("offer_title"), rs.getInt("quantity"),
                        rs.getObject("pickup_start", java.time.OffsetDateTime.class).toInstant(),
                        rs.getObject("pickup_end", java.time.OffsetDateTime.class).toInstant(),
                        rs.getLong("total_price_minor"), rs.getString("status"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant())).list();
    }

    private UUID requirePermission(UUID userId, String permission) {
        UUID merchantId = merchants.resolveActingMerchant(userId)
                .orElseThrow(() -> new AccessDeniedException("Merchant access required."));
        if (!merchants.isApproved(merchantId) || !merchants.can(userId, merchantId, permission))
            throw new AccessDeniedException("Your shop role does not allow this action.");
        return merchantId;
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || from.isBefore(to.minusDays(90)))
            throw new IllegalArgumentException("Sales range must be between 1 and 91 days.");
    }
    private static String customerInitials(String name) {
        if (name == null || name.isBlank()) return "Customer";
        String[] parts = name.trim().split("\\s+");
        return parts[0] + (parts.length > 1 ? " " + parts[parts.length - 1].substring(0, 1).toUpperCase() + "." : "");
    }
    private static String csv(String value) { return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\""; }

    private record Shop(String name, String city) {}
    private record Stats(long liveOffers, long reservedToday, long basketsToday, long toCollectNow, long collectedToday) {}
    private record Totals(long basketsCollected, long salesMinor, long cancelled, long notCollected) {}
    public record LiveOffer(UUID id, String title, int priceMinor, int quantityTotal, int quantityReserved, String status) {}
    public record OrderRow(String reference, String customerName, String basket, int quantity,
                           java.time.Instant pickupStart, java.time.Instant pickupEnd, long amountMinor,
                           String status, java.time.Instant createdAt) {}
    public record DailySale(LocalDate date, long amountMinor) {}
    public record Dashboard(String shopName, String city, long liveOffers, long reservedToday,
                            long basketsToday, long toCollectNow, long collectedToday,
                            List<OrderRow> todayOrders, List<LiveOffer> offers) {}
    public record Sales(long basketsCollected, long salesMinor, long cancelled, long notCollected,
                        LocalDate from, LocalDate to, List<DailySale> daily, List<OrderRow> latestOrders) {}
}
