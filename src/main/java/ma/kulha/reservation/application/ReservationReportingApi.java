package ma.kulha.reservation.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** Read-only reporting contract for merchant operations; all SQL remains reservation-owned. */
public interface ReservationReportingApi {
    DashboardStats dashboardStats(UUID merchantId, Instant dayStart, Instant dayEnd, Instant now);
    List<OrderSnapshot> listMerchantOrders(UUID merchantId, String filter, int limit, Instant dayStart, Instant dayEnd);
    SalesReport salesReport(UUID merchantId, LocalDate from, LocalDate to, ZoneId zone);
    List<OrderSnapshot> merchantOrdersForCreatedRange(UUID merchantId, LocalDate from, LocalDate to, ZoneId zone, int limit);
    List<SalesExportSnapshot> completedSalesForRange(UUID merchantId, LocalDate from, LocalDate to, ZoneId zone);

    record DashboardStats(long reservedToday, long basketsToday, long toCollectNow, long collectedToday) {}
    record OrderSnapshot(UUID customerId, String reference, String offerTitle, int quantity,
                         Instant pickupStart, Instant pickupEnd, long totalPriceMinor,
                         String status, Instant createdAt) {}
    record DailySale(LocalDate date, long amountMinor) {}
    record SalesExportSnapshot(String reference, String basket, int quantity, long totalPriceMinor, Instant completedAt) {}
    record SalesReport(long basketsCollected, long salesMinor, long cancelled, long notCollected,
                       List<DailySale> daily, List<OrderSnapshot> latestOrders) {}
}
