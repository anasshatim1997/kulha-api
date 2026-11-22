package ma.kulha.support.application;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import ma.kulha.admin.application.AuditApi;
import ma.kulha.notification.application.NotificationOutbox;
import ma.kulha.offer.application.AdminOfferModerationService;
import ma.kulha.offer.application.OfferApplicationService;
import ma.kulha.reservation.domain.model.Reservation;
import ma.kulha.reservation.domain.repository.ReservationRepository;
import ma.kulha.support.interfaces.dto.OpenSupportCaseRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SupportCaseService {
    private static final UUID SYSTEM_ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String CASE_SELECT = """
            SELECT c.id, c.reference, c.category, c.subject, c.description, c.priority, c.status,
                   c.customer_user_id, u.full_name AS customer_name, c.merchant_id, m.name AS merchant_name,
                   c.offer_id, o.title AS offer_title, c.reservation_reference, c.assigned_to,
                   assignee.full_name AS assignee_name, c.hidden_offer, c.created_at, c.updated_at, c.resolved_at
            FROM admin.support_cases c
            JOIN identity.users u ON u.id=c.customer_user_id
            LEFT JOIN merchant.merchants m ON m.id=c.merchant_id
            LEFT JOIN offer.offers o ON o.id=c.offer_id
            LEFT JOIN identity.users assignee ON assignee.id=c.assigned_to
            """;

    private final JdbcClient jdbc;
    private final ReservationRepository reservations;
    private final OfferApplicationService offers;
    private final AdminOfferModerationService offerModeration;
    private final AuditApi audit;
    private final NotificationOutbox notifications;
    private final Clock clock;

    SupportCaseService(JdbcClient jdbc, ReservationRepository reservations, OfferApplicationService offers,
                       AdminOfferModerationService offerModeration, AuditApi audit,
                       NotificationOutbox notifications, Clock clock) {
        this.jdbc = jdbc;
        this.reservations = reservations;
        this.offers = offers;
        this.offerModeration = offerModeration;
        this.audit = audit;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional
    public CaseDetail open(UUID customerId, OpenSupportCaseRequest request) {
        String category = request.category();
        Reservation reservation = null;
        if (request.reservationReference() != null && !request.reservationReference().isBlank()) {
            reservation = reservations.findByReference(request.reservationReference().trim())
                    .filter(found -> found.customerId().equals(customerId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found."));
        }
        if ("FOOD_SAFETY".equals(category) && reservation == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A food-safety report must reference a reservation.");
        }

        UUID id = UUID.randomUUID();
        String reference = "KLH-C-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        UUID merchantId = reservation == null ? null : reservation.merchantId();
        UUID offerId = reservation == null ? null : reservation.offerId();
        String priority = "FOOD_SAFETY".equals(category) ? "HIGH" : "MEDIUM";
        Instant now = clock.instant();
        boolean hideOffer = false;
        if (offerId != null && "FOOD_SAFETY".equals(category)) {
            AdminOfferModerationService.OfferRow row = offerModeration.get(offerId);
            if ("PUBLISHED".equals(row.status())) {
                offers.adminSuspend(SYSTEM_ACTOR, offerId, "Food safety report under review");
            }
            hideOffer = true;
        }

        jdbc.sql("""
                INSERT INTO admin.support_cases
                    (id, reference, category, subject, description, priority, status, customer_user_id,
                     merchant_id, offer_id, reservation_reference, hidden_offer, created_at, updated_at)
                VALUES (:id, :reference, :category, :subject, :description, :priority, 'OPEN', :customer,
                        :merchant, :offer, :reservation, :hidden, :now, :now)
                """)
                .param("id", id).param("reference", reference).param("category", category)
                .param("subject", request.subject().trim()).param("description", request.description().trim())
                .param("priority", priority).param("customer", customerId).param("merchant", merchantId)
                .param("offer", offerId).param("reservation", reservation == null ? null : reservation.reference())
                .param("hidden", hideOffer).param("now", now.atOffset(java.time.ZoneOffset.UTC)).update();

        String customerName = jdbc.sql("SELECT full_name FROM identity.users WHERE id=:id")
                .param("id", customerId).query(String.class).single();
        appendTimeline(id, "CUSTOMER", customerId, "Customer", "CUSTOMER", request.description().trim());
        if (hideOffer) appendTimeline(id, "SYSTEM", null, "KULHA!", "INTERNAL",
                "The offer was hidden from customers while the food-safety case is reviewed.");
        audit.append(customerId, "SUPPORT_CASE_OPENED", "SUPPORT_CASE", reference, category, "SUCCESS");
        return get(reference);
    }

    @Transactional(readOnly = true)
    public CasePage list(String status, String category, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.clamp(size, 1, 100);
        List<String> conditions = new ArrayList<>();
        if (status != null && !status.isBlank()) conditions.add("c.status=:status");
        if (category != null && !category.isBlank()) conditions.add("c.category=:category");
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        JdbcClient.StatementSpec query = jdbc.sql(CASE_SELECT + where
                + " ORDER BY CASE c.priority WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END, c.created_at DESC LIMIT :limit OFFSET :offset")
                .param("limit", safeSize).param("offset", safePage * safeSize);
        JdbcClient.StatementSpec count = jdbc.sql("SELECT count(*) FROM admin.support_cases c" + where);
        if (status != null && !status.isBlank()) { query=query.param("status",status); count=count.param("status",status); }
        if (category != null && !category.isBlank()) { query=query.param("category",category); count=count.param("category",category); }
        List<CaseSummary> items = query.query((rs,row)->summary(rs)).list();
        return new CasePage(items, safePage, safeSize, count.query(Long.class).single());
    }

    @Transactional(readOnly = true)
    public CaseDetail get(String reference) {
        CaseSummary summary = jdbc.sql(CASE_SELECT + " WHERE c.reference=:reference")
                .param("reference", reference)
                .query((rs,row)->summary(rs)).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Support case not found."));
        List<TimelineItem> timeline = jdbc.sql("""
                SELECT id, author_type, author_user_id, author_label, visibility, message, created_at
                FROM admin.support_case_timeline WHERE case_id=:id ORDER BY created_at, id
                """).param("id", summary.id())
                .query((rs,row)->new TimelineItem(rs.getObject("id",UUID.class), rs.getString("author_type"),
                        rs.getObject("author_user_id",UUID.class), rs.getString("author_label"),
                        rs.getString("visibility"), rs.getString("message"),
                        rs.getObject("created_at",OffsetDateTime.class).toInstant())).list();
        return new CaseDetail(summary, timeline);
    }

    @Transactional
    public void addMessage(UUID adminId, String reference, String message, boolean sendToCustomer) {
        CaseSummary item = get(reference).summary();
        String clean = message == null ? "" : message.trim();
        if (clean.isEmpty() || clean.length() > 3000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message is required.");
        String actor = jdbc.sql("SELECT full_name FROM identity.users WHERE id=:id")
                .param("id", adminId).query(String.class).optional().orElse("KULHA support");
        appendTimeline(item.id(), "ADMIN", adminId, actor, sendToCustomer ? "CUSTOMER" : "INTERNAL", clean);
        if (sendToCustomer) {
            notifications.enqueue(item.customerUserId(), "SUPPORT_CASE_UPDATE",
                    "Mise à jour de votre dossier", "Un membre de l’équipe KULHA a répondu à votre dossier " + reference + ".",
                    "Voir les notifications", "/notifications");
        }
        audit.append(adminId, sendToCustomer ? "SUPPORT_MESSAGE_SENT" : "SUPPORT_INTERNAL_NOTE_ADDED",
                "SUPPORT_CASE", reference, "Message added", "SUCCESS");
    }

    @Transactional
    public void assign(UUID adminId, String reference, UUID assignedTo) {
        CaseSummary item = get(reference).summary();
        boolean eligible = jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM identity.users u
                    JOIN identity.user_roles r ON r.user_id = u.id
                    WHERE u.id = :id
                      AND u.status = 'ACTIVE'
                      AND r.role IN ('SUPPORT', 'ADMIN', 'SUPER_ADMIN')
                )
                """)
                .param("id", assignedTo).query(Boolean.class).single();
        if (!eligible) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Eligible active assignee not found.");
        }
        jdbc.sql("UPDATE admin.support_cases SET assigned_to=:assignee, updated_at=:now WHERE id=:id")
                .param("assignee", assignedTo).param("now", clock.instant().atOffset(java.time.ZoneOffset.UTC))
                .param("id", item.id()).update();
        audit.append(adminId, "SUPPORT_CASE_ASSIGNED", "SUPPORT_CASE", reference, "Assigned case", "SUCCESS");
        appendTimeline(item.id(), "SYSTEM", null, "KULHA!", "INTERNAL", "Case assigned to " + assignedTo);
    }

    @Transactional
    public void act(UUID adminId, String reference, String action, String reason) {
        CaseSummary item = get(reference).summary();
        String cleanReason = reason == null ? "" : reason.trim();
        if (cleanReason.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reason is required.");
        Instant now = clock.instant();
        switch (action) {
            case "CONTACT_SHOP" -> {
                if (item.merchantId() == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "This case has no linked shop.");
                UUID owner = jdbc.sql("SELECT owner_user_id FROM merchant.merchants WHERE id=:id")
                        .param("id", item.merchantId()).query(UUID.class).single();
                jdbc.sql("UPDATE admin.support_cases SET status='WAITING_REPLY', updated_at=:now WHERE id=:id")
                        .param("now", now.atOffset(java.time.ZoneOffset.UTC)).param("id", item.id()).update();
                notifications.enqueue(owner, "SUPPORT_CASE_UPDATE", "KULHA support needs your reply",
                        "Please contact KULHA about support case " + reference + ".",
                        "Open KULHA", "/merchant/dashboard");
                appendTimeline(item.id(), "SYSTEM", null, "KULHA!", "INTERNAL", "Shop contacted: " + cleanReason);
            }
            case "ESCALATE" -> {
                jdbc.sql("UPDATE admin.support_cases SET status='ESCALATED', updated_at=:now WHERE id=:id")
                        .param("now", now.atOffset(java.time.ZoneOffset.UTC)).param("id", item.id()).update();
                appendTimeline(item.id(), "ADMIN", adminId, "KULHA support", "INTERNAL", "Case escalated: " + cleanReason);
            }
            case "RESOLVE" -> {
                jdbc.sql("UPDATE admin.support_cases SET status='RESOLVED', resolved_at=:now, updated_at=:now WHERE id=:id")
                        .param("now", now.atOffset(java.time.ZoneOffset.UTC)).param("id", item.id()).update();
                appendTimeline(item.id(), "ADMIN", adminId, "KULHA support", "INTERNAL", "Case resolved: " + cleanReason);
            }
            case "KEEP_OFFER_HIDDEN" -> {
                if (item.offerId() == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "This case has no linked offer.");
                AdminOfferModerationService.OfferRow offer = offerModeration.get(item.offerId());
                if ("PUBLISHED".equals(offer.status())) offers.adminSuspend(adminId, item.offerId(), cleanReason);
                jdbc.sql("UPDATE admin.support_cases SET hidden_offer=TRUE, updated_at=:now WHERE id=:id")
                        .param("now", now.atOffset(java.time.ZoneOffset.UTC)).param("id", item.id()).update();
                appendTimeline(item.id(), "ADMIN", adminId, "KULHA support", "INTERNAL", "Offer kept hidden: " + cleanReason);
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported case action.");
        }
        audit.append(adminId, "SUPPORT_" + action, "SUPPORT_CASE", reference, cleanReason, "SUCCESS");
    }

    @Transactional
    public CustomerDetails revealCustomer(UUID adminId, String reference) {
        CaseSummary item = get(reference).summary();
        CustomerDetails details = jdbc.sql("SELECT full_name, email, phone FROM identity.users WHERE id=:id")
                .param("id", item.customerUserId())
                .query((rs,row)->new CustomerDetails(rs.getString("full_name"),rs.getString("email"),rs.getString("phone")))
                .single();
        audit.append(adminId, "SUPPORT_CUSTOMER_DETAILS_REVEALED", "SUPPORT_CASE", reference,
                "Customer details revealed to support", "SUCCESS");
        return details;
    }

    private void appendTimeline(UUID caseId, String authorType, UUID authorUserId, String authorLabel,
                                String visibility, String message) {
        jdbc.sql("""
                INSERT INTO admin.support_case_timeline
                    (id, case_id, author_type, author_user_id, author_label, visibility, message, created_at)
                VALUES (:id, :case, :type, :user, :label, :visibility, :message, :created)
                """).param("id", UUID.randomUUID()).param("case", caseId).param("type", authorType)
                .param("user", authorUserId).param("label", authorLabel).param("visibility", visibility)
                .param("message", message).param("created", clock.instant().atOffset(java.time.ZoneOffset.UTC)).update();
    }

    private static CaseSummary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CaseSummary(rs.getObject("id",UUID.class),rs.getString("reference"),rs.getString("category"),
                rs.getString("subject"),rs.getString("description"),rs.getString("priority"),rs.getString("status"),
                rs.getObject("customer_user_id",UUID.class),mask(rs.getString("customer_name")),
                rs.getObject("merchant_id",UUID.class),rs.getString("merchant_name"),
                rs.getObject("offer_id",UUID.class),rs.getString("offer_title"),rs.getString("reservation_reference"),
                rs.getObject("assigned_to",UUID.class),rs.getString("assignee_name"),rs.getBoolean("hidden_offer"),
                rs.getObject("created_at",OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at",OffsetDateTime.class).toInstant(),
                rs.getObject("resolved_at",OffsetDateTime.class)==null?null:rs.getObject("resolved_at",OffsetDateTime.class).toInstant());
    }
    private static String mask(String fullName) {
        if (fullName == null || fullName.isBlank()) return "Customer";
        String[] parts=fullName.trim().split("\\s+");
        return parts[0].substring(0,1).toUpperCase(Locale.ROOT)+"•••••"+
                (parts.length>1 ? " "+parts[parts.length-1].substring(0,1).toUpperCase(Locale.ROOT)+"." : "");
    }

    public record CaseSummary(UUID id,String reference,String category,String subject,String description,
            String priority,String status,UUID customerUserId,String customerLabel,UUID merchantId,
            String merchantName,UUID offerId,String offerTitle,String reservationReference,
            UUID assignedTo,String assigneeName,boolean hiddenOffer,Instant createdAt,Instant updatedAt,Instant resolvedAt) {}
    public record TimelineItem(UUID id,String authorType,UUID authorUserId,String authorLabel,String visibility,
            String message,Instant createdAt) {}
    public record CaseDetail(CaseSummary summary,List<TimelineItem> timeline) {}
    public record CasePage(List<CaseSummary> items,int page,int size,long total) {}
    public record CustomerDetails(String fullName,String email,String phone) {}
}
