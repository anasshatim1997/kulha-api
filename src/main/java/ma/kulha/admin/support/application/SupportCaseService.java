package ma.kulha.admin.support.application;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import ma.kulha.admin.application.AuditApi;
import ma.kulha.notification.application.NotificationOutbox;
import ma.kulha.identity.application.IdentityApi;
import ma.kulha.merchant.application.MerchantApi;
import ma.kulha.offer.application.OfferApi;
import ma.kulha.reservation.application.ReservationSupportApi;
import ma.kulha.offer.application.OfferApplicationService;
import ma.kulha.shared.identity.UserAccountDirectory;
import ma.kulha.admin.support.interfaces.dto.OpenSupportCaseRequest;
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
                   c.customer_user_id, c.merchant_id, c.offer_id, c.reservation_reference, c.assigned_to,
                   c.hidden_offer, c.created_at, c.updated_at, c.resolved_at
            FROM admin.support_cases c
            """;

    private final JdbcClient jdbc;
    private final ReservationSupportApi reservations;
    private final OfferApplicationService offers;
    private final OfferApi offerApi;
    private final IdentityApi identities;
    private final MerchantApi merchants;
    private final AuditApi audit;
    private final NotificationOutbox notifications;
    private final Clock clock;
    private final UserAccountDirectory userAccounts;

    SupportCaseService(JdbcClient jdbc, ReservationSupportApi reservations, OfferApplicationService offers,
                       OfferApi offerApi, IdentityApi identities, MerchantApi merchants, AuditApi audit,
                       NotificationOutbox notifications, Clock clock, UserAccountDirectory userAccounts) {
        this.jdbc = jdbc;
        this.reservations = reservations;
        this.offers = offers;
        this.offerApi = offerApi;
        this.identities = identities;
        this.merchants = merchants;
        this.audit = audit;
        this.notifications = notifications;
        this.clock = clock;
        this.userAccounts = userAccounts;
    }

    @Transactional
    public CaseDetail open(UUID customerId, OpenSupportCaseRequest request) {
        String category = request.category();
        IdentityApi.SupportUserProfile customer = identities.findSupportProfile(customerId)
                .filter(IdentityApi.SupportUserProfile::active)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "Customer account is no longer available."));
        ReservationSupportApi.ReservationReference reservation = null;
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
        UUID merchantId = null;
        UUID offerId = null;
        MerchantApi.SupportMerchantProfile merchant = null;
        OfferApi.SupportOfferProfile offer = null;
        if (reservation != null) {
            if (reservation.merchantId() != null) {
                merchant = merchants.findSupportProfiles(List.of(reservation.merchantId())).stream().findFirst().orElse(null);
                if (merchant != null) merchantId = merchant.id();
            }
            if (reservation.offerId() != null) {
                offer = offerApi.findSupportProfiles(List.of(reservation.offerId())).stream().findFirst().orElse(null);
                if (offer != null) {
                    if (merchant != null && !merchant.id().equals(offer.merchantId())) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Reservation references inconsistent shop and offer records.");
                    }
                    offerId = offer.id();
                }
            }
        }
        String priority = "FOOD_SAFETY".equals(category) ? "HIGH" : "MEDIUM";
        Instant now = clock.instant();
        boolean hideOffer = "FOOD_SAFETY".equals(category) && offer != null;
        if (hideOffer && "PUBLISHED".equals(offer.status())) {
            offers.adminSuspend(SYSTEM_ACTOR, offerId, "Food safety report under review");
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

        appendTimeline(id, "CUSTOMER", customerId, "Customer", "CUSTOMER", request.description().trim());
        if (hideOffer) appendTimeline(id, "SYSTEM", null, "KULHA!", "INTERNAL",
                "The offer was hidden from customers while the food-safety case is reviewed.");
        audit.append(customerId, "SUPPORT_CASE_OPENED", "SUPPORT_CASE", reference, category, "SUCCESS");
        return get(reference);
    }

    @Transactional(readOnly = true)
    public CasePage list(UUID actorId, String status, String category, int page, int size) {
        Set<String> roles = identities.findActiveRoleNames(actorId);
        boolean support = roles.contains("SUPPORT");
        boolean administrator = roles.contains("ADMIN");
        boolean superAdministrator = roles.contains("SUPER_ADMIN");
        if (!support && !administrator && !superAdministrator) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Support workspace access denied.");
        int safePage = Math.max(0, page);
        int safeSize = Math.clamp(size, 1, 100);
        List<String> conditions = new ArrayList<>();
        if (status != null && !status.isBlank()) conditions.add("c.status=:status");
        if (category != null && !category.isBlank()) conditions.add("c.category=:category");
        if (support && !administrator && !superAdministrator) conditions.add("c.assigned_to=:actorId");
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        JdbcClient.StatementSpec query = jdbc.sql(CASE_SELECT + where
                + " ORDER BY CASE c.priority WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END, c.created_at DESC LIMIT :limit OFFSET :offset")
                .param("limit", safeSize).param("offset", safePage * safeSize);
        JdbcClient.StatementSpec count = jdbc.sql("SELECT count(*) FROM admin.support_cases c" + where);
        if (status != null && !status.isBlank()) { query=query.param("status",status); count=count.param("status",status); }
        if (category != null && !category.isBlank()) { query=query.param("category",category); count=count.param("category",category); }
        if (support && !administrator && !superAdministrator) { query=query.param("actorId",actorId); count=count.param("actorId",actorId); }
        List<CaseSummary> items = enrich(query.query((rs,row)->summary(rs)).list());
        return new CasePage(items, safePage, safeSize, count.query(Long.class).single());
    }

    @Transactional(readOnly = true)
    public CaseDetail getForActor(UUID actorId, String reference) {
        CaseSummary item = requireCaseAccess(actorId, reference);
        return get(item.reference());
    }

    @Transactional(readOnly = true)
    public CaseDetail get(String reference) {
        CaseSummary summary = jdbc.sql(CASE_SELECT + " WHERE c.reference=:reference")
                .param("reference", reference)
                .query((rs,row)->summary(rs)).optional()
                .map(row -> enrich(List.of(row)).get(0))
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
        CaseSummary item = requireCaseAccess(adminId, reference);
        String clean = message == null ? "" : message.trim();
        if (clean.isEmpty() || clean.length() > 3000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message is required.");
        String actor = identities.findSupportProfile(adminId).filter(IdentityApi.SupportUserProfile::active)
                .map(IdentityApi.SupportUserProfile::fullName).orElse("KULHA support");
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
        requireAdministrator(adminId);
        CaseSummary item = get(reference).summary();
        if (!userAccounts.isEligibleSupportCaseAssignee(assignedTo)) {
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
        Set<String> roles = identities.findActiveRoleNames(adminId);
        CaseSummary item = requireCaseAccess(adminId, reference, roles);
        boolean supportOnly = roles.contains(Role.SUPPORT)
                && !roles.contains(Role.ADMIN) && !roles.contains(Role.SUPER_ADMIN);
        if (supportOnly && "KEEP_OFFER_HIDDEN".equals(action)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Offer moderation is not part of support-agent access.");
        }
        String cleanReason = reason == null ? "" : reason.trim();
        if (cleanReason.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reason is required.");
        Instant now = clock.instant();
        switch (action) {
            case "CONTACT_SHOP" -> {
                if (item.merchantId() == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "This case has no linked shop.");
                MerchantApi.SupportMerchantProfile merchant = merchants.findSupportProfiles(List.of(item.merchantId()))
                        .stream().findFirst()
                        .filter(found -> "APPROVED".equals(found.status()))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                                "The linked shop is no longer active."));
                UUID owner = merchant.ownerUserId();
                if (identities.findSupportProfile(owner).filter(IdentityApi.SupportUserProfile::active).isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "The linked shop owner is no longer active.");
                }
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
                OfferApi.SupportOfferProfile offer = offerApi.findSupportProfiles(List.of(item.offerId()))
                        .stream().findFirst().orElse(null);
                if (offer != null && "PUBLISHED".equals(offer.status())) {
                    offers.adminSuspend(adminId, item.offerId(), cleanReason);
                }
                jdbc.sql("UPDATE admin.support_cases SET hidden_offer=TRUE, updated_at=:now WHERE id=:id")
                        .param("now", now.atOffset(java.time.ZoneOffset.UTC)).param("id", item.id()).update();
                appendTimeline(item.id(), "ADMIN", adminId, "KULHA support", "INTERNAL", "Offer kept hidden: " + cleanReason);
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported case action.");
        }
        audit.append(adminId, "SUPPORT_" + action, "SUPPORT_CASE", reference, cleanReason, "SUCCESS");
    }

    @Transactional
    public CustomerDetails revealCustomer(UUID actorId, String reference, String reason) {
        String cleanReason = reason == null ? "" : reason.trim();
        if (cleanReason.isEmpty() || cleanReason.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A disclosure reason is required.");
        }
        Set<String> roles = identities.findActiveRoleNames(actorId);
        CaseSummary item = requireCaseAccess(actorId, reference, roles);
        boolean superAdministrator = roles.contains(Role.SUPER_ADMIN);
        boolean assignedSupport = !roles.contains(Role.ADMIN)
                && !superAdministrator && roles.contains(Role.SUPPORT)
                && actorId.equals(item.assignedTo());
        if (!superAdministrator && !assignedSupport) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Customer disclosure is not permitted.");
        }
        IdentityApi.SupportUserProfile profile = identities.findSupportProfile(item.customerUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "Customer account is no longer available."));
        CustomerDetails details = new CustomerDetails(profile.fullName(), profile.email(), profile.phone());
        audit.append(actorId, "SUPPORT_CUSTOMER_DETAILS_REVEALED", "SUPPORT_CASE", reference, cleanReason, "SUCCESS");
        return details;
    }

    private CaseSummary requireCaseAccess(UUID actorId, String reference) {
        return requireCaseAccess(actorId, reference, identities.findActiveRoleNames(actorId));
    }

    private CaseSummary requireCaseAccess(UUID actorId, String reference, Set<String> roles) {
        boolean superAdministrator = roles.contains(Role.SUPER_ADMIN);
        boolean administrator = roles.contains(Role.ADMIN);
        boolean support = roles.contains(Role.SUPPORT);
        if (!superAdministrator && !administrator && !support) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Support case access denied.");
        }
        CaseSummary item = get(reference).summary();
        if (support && !administrator && !superAdministrator && !actorId.equals(item.assignedTo())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Support case not found.");
        }
        return item;
    }

    private void requireAdministrator(UUID actorId) {
        Set<Role> roles = userAccounts.findActiveRoles(actorId);
        if (!roles.contains(Role.ADMIN) && !roles.contains(Role.SUPER_ADMIN)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrative support access required.");
        }
    }

    private void appendTimeline(UUID caseId, String authorType, UUID authorUserId, String authorLabel,
                                String visibility, String message) {
        if (authorUserId != null && identities.findSupportProfile(authorUserId)
                .filter(IdentityApi.SupportUserProfile::active).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.GONE, "Timeline author account is no longer active.");
        }
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
                rs.getObject("customer_user_id",UUID.class),"Customer",
                rs.getObject("merchant_id",UUID.class),null,
                rs.getObject("offer_id",UUID.class),null,rs.getString("reservation_reference"),
                rs.getObject("assigned_to",UUID.class),null,rs.getBoolean("hidden_offer"),
                rs.getObject("created_at",OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at",OffsetDateTime.class).toInstant(),
                rs.getObject("resolved_at",OffsetDateTime.class)==null?null:rs.getObject("resolved_at",OffsetDateTime.class).toInstant());
    }
    private List<CaseSummary> enrich(List<CaseSummary> cases) {
        if (cases.isEmpty()) return List.of();
        Set<UUID> userIds = new java.util.LinkedHashSet<>();
        Set<UUID> merchantIds = new java.util.LinkedHashSet<>();
        Set<UUID> offerIds = new java.util.LinkedHashSet<>();
        for (CaseSummary item : cases) {
            userIds.add(item.customerUserId());
            if (item.assignedTo() != null) userIds.add(item.assignedTo());
            if (item.merchantId() != null) merchantIds.add(item.merchantId());
            if (item.offerId() != null) offerIds.add(item.offerId());
        }
        java.util.Map<UUID, IdentityApi.SupportUserProfile> userProfiles = identities.findSupportProfiles(userIds)
                .stream().collect(java.util.stream.Collectors.toMap(IdentityApi.SupportUserProfile::id, p -> p));
        java.util.Map<UUID, MerchantApi.SupportMerchantProfile> merchantProfiles = merchants.findSupportProfiles(merchantIds)
                .stream().collect(java.util.stream.Collectors.toMap(MerchantApi.SupportMerchantProfile::id, p -> p));
        java.util.Map<UUID, OfferApi.SupportOfferProfile> offerProfiles = offerApi.findSupportProfiles(offerIds)
                .stream().collect(java.util.stream.Collectors.toMap(OfferApi.SupportOfferProfile::id, p -> p));
        return cases.stream().map(item -> {
            IdentityApi.SupportUserProfile customer = userProfiles.get(item.customerUserId());
            IdentityApi.SupportUserProfile assignee = item.assignedTo() == null ? null : userProfiles.get(item.assignedTo());
            MerchantApi.SupportMerchantProfile merchant = item.merchantId() == null ? null : merchantProfiles.get(item.merchantId());
            OfferApi.SupportOfferProfile offer = item.offerId() == null ? null : offerProfiles.get(item.offerId());
            return new CaseSummary(item.id(), item.reference(), item.category(), item.subject(), item.description(),
                    item.priority(), item.status(), item.customerUserId(),
                    customer == null || !customer.active() ? "Customer unavailable" : mask(customer.fullName()),
                    item.merchantId(), merchant == null ? "Shop unavailable" : merchant.name(),
                    item.offerId(), offer == null ? "Offer unavailable" : offer.title(), item.reservationReference(),
                    item.assignedTo(), assignee == null || !assignee.active() ? "Former agent" : assignee.fullName(),
                    item.hiddenOffer(), item.createdAt(), item.updatedAt(), item.resolvedAt());
        }).toList();
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
