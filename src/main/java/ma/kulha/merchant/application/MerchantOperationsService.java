package ma.kulha.merchant.application;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import ma.kulha.admin.application.AuditApi;
import ma.kulha.identity.application.IdentityApi;
import ma.kulha.merchant.application.MerchantApi.SupportMerchantProfile;
import ma.kulha.offer.application.OfferApi;
import ma.kulha.reservation.application.ReservationReportingApi;
import ma.kulha.reservation.application.ReservationReportingApi.OrderSnapshot;
import ma.kulha.shared.csv.CsvEscaper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantOperationsService {
    private static final ZoneId SHOP_ZONE = ZoneId.of("Africa/Casablanca");
    private final MerchantApi merchants;
    private final OfferApi offers;
    private final ReservationReportingApi reservations;
    private final IdentityApi identities;
    private final AuditApi audit;
    private final Clock clock;

    MerchantOperationsService(MerchantApi merchants, OfferApi offers, ReservationReportingApi reservations,
                              IdentityApi identities, AuditApi audit, Clock clock) {
        this.merchants = merchants;
        this.offers = offers;
        this.reservations = reservations;
        this.identities = identities;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(UUID userId) {
        UUID merchantId = requirePermission(userId, "VIEW_ORDERS");
        LocalDate today = LocalDate.now(clock.withZone(SHOP_ZONE));
        var dayStart = today.atStartOfDay(SHOP_ZONE).toInstant();
        var dayEnd = today.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant();
        var stats = reservations.dashboardStats(merchantId, dayStart, dayEnd, clock.instant());
        SupportMerchantProfile shop = merchants.findSupportProfiles(List.of(merchantId)).stream().findFirst()
                .orElseThrow(() -> new AccessDeniedException("Merchant record is unavailable."));
        OfferApi.MerchantDashboardOffers offerDashboard = offers.merchantDashboard(merchantId, 8);
        List<LiveOffer> liveOffers = offerDashboard.offers().stream()
                .map(item -> new LiveOffer(item.id(), item.title(), item.priceMinor(), item.quantityTotal(),
                        item.quantityReserved(), item.status())).toList();
        return new Dashboard(shop.name(), shop.city(), offerDashboard.liveOffers(), stats.reservedToday(),
                stats.basketsToday(), stats.toCollectNow(), stats.collectedToday(),
                enrichOrders(reservations.listMerchantOrders(merchantId, "TODAY", 8, dayStart, dayEnd)),
                liveOffers);
    }

    @Transactional(readOnly = true)
    public List<OrderRow> orders(UUID userId, String filter) {
        UUID merchantId = requirePermission(userId, "VIEW_ORDERS");
        LocalDate today = LocalDate.now(clock.withZone(SHOP_ZONE));
        var dayStart = today.atStartOfDay(SHOP_ZONE).toInstant();
        var dayEnd = today.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant();
        return enrichOrders(reservations.listMerchantOrders(merchantId,
                filter == null ? "ALL" : filter, 100, dayStart, dayEnd));
    }

    @Transactional(readOnly = true)
    public Sales sales(UUID ownerId, LocalDate from, LocalDate to) {
        UUID merchantId = merchants.findMerchantIdByOwner(ownerId)
                .orElseThrow(() -> new AccessDeniedException("Merchant owner access required."));
        if (!merchants.isApproved(merchantId)) throw new AccessDeniedException("Approved merchant access required.");
        LocalDate end = to == null ? LocalDate.now(clock.withZone(SHOP_ZONE)) : to;
        LocalDate start = from == null ? end.minusDays(6) : from;
        validateRange(start, end);

        ReservationReportingApi.SalesReport report = reservations.salesReport(merchantId, start, end, SHOP_ZONE);
        List<DailySale> daily = report.daily().stream()
                .map(item -> new DailySale(item.date(), item.amountMinor())).toList();
        return new Sales(report.basketsCollected(), report.salesMinor(), report.cancelled(),
                report.notCollected(), start, end, daily, enrichOrders(report.latestOrders()));
    }

    @Transactional
    public String exportSales(UUID ownerId, LocalDate from, LocalDate to) {
        StringWriter writer = new StringWriter();
        try {
            writeSalesExport(ownerId, from, to, writer);
        } catch (IOException impossible) {
            throw new IllegalStateException("Writing to an in-memory CSV buffer failed.", impossible);
        }
        return writer.toString();
    }

    @Transactional
    public void writeSalesExport(UUID ownerId, LocalDate from, LocalDate to, Writer writer) throws IOException {
        Sales sales = sales(ownerId, from, to);
        UUID merchantId = merchants.findMerchantIdByOwner(ownerId).orElseThrow();
        audit.append(ownerId, "MERCHANT_SALES_EXPORTED", "MERCHANT", merchantId.toString(),
                sales.from() + " to " + sales.to(), "SUCCESS");
        writer.write("date,reference,basket,quantity,amount_mad,status\n");

        java.time.Instant afterCompletedAt = null;
        UUID afterReservationId = null;
        while (true) {
            List<ReservationReportingApi.SalesExportBatchRow> batch = reservations.completedSalesBatch(
                    merchantId, sales.from(), sales.to(), SHOP_ZONE, afterCompletedAt, afterReservationId, 500);
            if (batch.isEmpty()) break;
            for (ReservationReportingApi.SalesExportBatchRow row : batch) {
                writer.write(row.completedAt().atZone(SHOP_ZONE).toLocalDate().toString());
                writer.write(',');
                writer.write(CsvEscaper.cell(row.reference()));
                writer.write(',');
                writer.write(CsvEscaper.cell(row.basket()));
                writer.write(',');
                writer.write(Integer.toString(row.quantity()));
                writer.write(',');
                writer.write(String.format(java.util.Locale.ROOT, "%.2f", row.totalPriceMinor() / 100.0));
                writer.write(',');
                writer.write(CsvEscaper.cell("COMPLETED"));
                writer.write('\n');
            }
            ReservationReportingApi.SalesExportBatchRow last = batch.get(batch.size() - 1);
            afterCompletedAt = last.completedAt();
            afterReservationId = last.id();
            if (batch.size() < 500) break;
        }
    }

    private List<OrderRow> enrichOrders(List<OrderSnapshot> orders) {
        if (orders.isEmpty()) return List.of();
        var customerIds = orders.stream().map(OrderSnapshot::customerId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, IdentityApi.SupportUserSummary> profiles = identities.findSupportSummaries(customerIds).stream()
                .collect(Collectors.toMap(IdentityApi.SupportUserSummary::id, profile -> profile));
        return orders.stream().map(order -> {
            IdentityApi.SupportUserSummary customer = order.customerId() == null ? null : profiles.get(order.customerId());
            return new OrderRow(order.reference(), customer == null ? "Customer" : customerInitials(customer.fullName()),
                    order.offerTitle(), order.quantity(), order.pickupStart(), order.pickupEnd(),
                    order.totalPriceMinor(), order.status(), order.createdAt());
        }).toList();
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
