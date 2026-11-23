package ma.kulha.reservation.application;

import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReservationReportingApiService implements ReservationReportingApi {
    private final JdbcClient jdbc;

    ReservationReportingApiService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public DashboardStats dashboardStats(UUID merchantId, Instant dayStart, Instant dayEnd, Instant now) {
        return jdbc.sql("""
                SELECT
                  count(*) FILTER (WHERE created_at >= :dayStart AND created_at < :dayEnd) AS reserved_today,
                  coalesce(sum(quantity) FILTER (WHERE created_at >= :dayStart AND created_at < :dayEnd), 0) AS baskets_today,
                  count(*) FILTER (WHERE status='CONFIRMED' AND pickup_start <= :now AND pickup_end > :now) AS to_collect_now,
                  count(*) FILTER (WHERE status='COMPLETED' AND updated_at >= :dayStart AND updated_at < :dayEnd) AS collected_today
                FROM reservation.reservations
                WHERE merchant_id=:merchant
                """)
                .param("merchant", merchantId).param("dayStart", db(dayStart), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("dayEnd", db(dayEnd), Types.TIMESTAMP_WITH_TIMEZONE).param("now", db(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .query((rs, row) -> new DashboardStats(rs.getLong("reserved_today"), rs.getLong("baskets_today"),
                        rs.getLong("to_collect_now"), rs.getLong("collected_today"))).single();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderSnapshot> listMerchantOrders(UUID merchantId, String filter, int limit,
                                                   Instant dayStart, Instant dayEnd) {
        String where = switch (filter) {
            case "TO_COLLECT" -> " AND status='CONFIRMED'";
            case "DONE" -> " AND status IN ('COMPLETED','CANCELLED','EXPIRED')";
            case "TODAY" -> " AND pickup_start >= :dayStart AND pickup_start < :dayEnd";
            case "ALL" -> "";
            default -> throw new IllegalArgumentException("Unsupported order filter.");
        };
        var query = jdbc.sql("""
                SELECT customer_id, reference, offer_title, quantity, pickup_start, pickup_end,
                       total_price_minor, status, created_at
                FROM reservation.reservations
                WHERE merchant_id=:merchant
                """ + where + " ORDER BY pickup_start DESC, created_at DESC LIMIT :limit")
                .param("merchant", merchantId).param("limit", limit);
        if ("TODAY".equals(filter)) {
            query = query.param("dayStart", db(dayStart), Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("dayEnd", db(dayEnd), Types.TIMESTAMP_WITH_TIMEZONE);
        }
        return query.query(ReservationReportingApiService::mapOrder).list();
    }

    @Override
    @Transactional(readOnly = true)
    public SalesReport salesReport(UUID merchantId, LocalDate from, LocalDate to, ZoneId zone) {
        String zoneId = zone.getId();
        Totals totals = jdbc.sql("""
                SELECT
                  coalesce(sum(quantity) FILTER (WHERE status='COMPLETED'),0) AS baskets_collected,
                  coalesce(sum(total_price_minor) FILTER (WHERE status='COMPLETED'),0) AS sales_minor,
                  count(*) FILTER (WHERE status='CANCELLED') AS cancelled,
                  count(*) FILTER (WHERE status='EXPIRED') AS not_collected
                FROM reservation.reservations
                WHERE merchant_id=:merchant
                  AND (updated_at AT TIME ZONE :zone)::date BETWEEN :fromDate AND :toDate
                """).param("merchant", merchantId).param("zone", zoneId).param("fromDate", from).param("toDate", to)
                .query((rs, row) -> new Totals(rs.getLong("baskets_collected"), rs.getLong("sales_minor"),
                        rs.getLong("cancelled"), rs.getLong("not_collected"))).single();

        List<DailySale> daily = jdbc.sql("""
                SELECT days.day::date AS sale_day, coalesce(sum(r.total_price_minor),0) AS amount_minor
                FROM generate_series(CAST(:fromDate AS date), CAST(:toDate AS date), interval '1 day') days(day)
                LEFT JOIN reservation.reservations r ON r.merchant_id=:merchant
                  AND r.status='COMPLETED'
                  AND (r.updated_at AT TIME ZONE :zone)::date=days.day::date
                GROUP BY days.day ORDER BY days.day
                """).param("fromDate", from).param("toDate", to).param("merchant", merchantId).param("zone", zoneId)
                .query((rs, row) -> new DailySale(rs.getObject("sale_day", LocalDate.class), rs.getLong("amount_minor")))
                .list();

        List<OrderSnapshot> latest = merchantOrdersForCreatedRange(merchantId, from, to, zone, 10);
        return new SalesReport(totals.basketsCollected(), totals.salesMinor(), totals.cancelled(),
                totals.notCollected(), daily, latest);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderSnapshot> merchantOrdersForCreatedRange(UUID merchantId, LocalDate from, LocalDate to,
                                                               ZoneId zone, int limit) {
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
        return jdbc.sql("""
                SELECT customer_id, reference, offer_title, quantity, pickup_start, pickup_end,
                       total_price_minor, status, created_at
                FROM reservation.reservations
                WHERE merchant_id=:merchant AND created_at >= :start AND created_at < :end
                ORDER BY created_at DESC LIMIT :limit
                """).param("merchant", merchantId).param("start", db(start), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("end", db(end), Types.TIMESTAMP_WITH_TIMEZONE).param("limit", limit)
                .query(ReservationReportingApiService::mapOrder).list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SalesExportSnapshot> completedSalesForRange(UUID merchantId, LocalDate from, LocalDate to, ZoneId zone) {
        return jdbc.sql("""
                SELECT reference, offer_title, quantity, total_price_minor, updated_at
                FROM reservation.reservations
                WHERE merchant_id=:merchant AND status='COMPLETED'
                  AND (updated_at AT TIME ZONE :zone)::date BETWEEN :fromDate AND :toDate
                ORDER BY updated_at DESC, id
                """).param("merchant", merchantId).param("zone", zone.getId())
                .param("fromDate", from).param("toDate", to)
                .query((rs, row) -> new SalesExportSnapshot(rs.getString("reference"), rs.getString("offer_title"),
                        rs.getInt("quantity"), rs.getLong("total_price_minor"),
                        instant(rs.getObject("updated_at", OffsetDateTime.class))))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SalesExportBatchRow> completedSalesBatch(UUID merchantId, LocalDate from, LocalDate to, ZoneId zone,
                                                          Instant afterCompletedAt, UUID afterReservationId, int limit) {
        String cursorClause = afterCompletedAt == null ? ""
                : " AND (updated_at, id) < (:afterCompletedAt, :afterReservationId)";
        var query = jdbc.sql("""
                SELECT id, reference, offer_title, quantity, total_price_minor, updated_at
                FROM reservation.reservations
                WHERE merchant_id=:merchant AND status='COMPLETED'
                  AND (updated_at AT TIME ZONE :zone)::date BETWEEN :fromDate AND :toDate
                """ + cursorClause + " ORDER BY updated_at DESC, id DESC LIMIT :limit")
                .param("merchant", merchantId).param("zone", zone.getId())
                .param("fromDate", from).param("toDate", to);
        if (afterCompletedAt != null) {
            query = query.param("afterCompletedAt", db(afterCompletedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("afterReservationId", afterReservationId);
        }
        return query.param("limit", Math.clamp(limit, 1, 1000))
                .query((rs, row) -> new SalesExportBatchRow(
                        rs.getObject("id", UUID.class), rs.getString("reference"), rs.getString("offer_title"),
                        rs.getInt("quantity"), rs.getLong("total_price_minor"),
                        instant(rs.getObject("updated_at", OffsetDateTime.class))))
                .list();
    }

    private static OrderSnapshot mapOrder(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new OrderSnapshot(rs.getObject("customer_id", UUID.class), rs.getString("reference"),
                rs.getString("offer_title"), rs.getInt("quantity"),
                instant(rs.getObject("pickup_start", OffsetDateTime.class)),
                instant(rs.getObject("pickup_end", OffsetDateTime.class)), rs.getLong("total_price_minor"),
                rs.getString("status"), instant(rs.getObject("created_at", OffsetDateTime.class)));
    }

    private static OffsetDateTime db(Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
    private record Totals(long basketsCollected, long salesMinor, long cancelled, long notCollected) {}
}
