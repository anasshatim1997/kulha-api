package ma.kulha.reservation.application;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.HexFormat;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import ma.kulha.merchant.application.MerchantApi;
import ma.kulha.offer.application.OfferApi;
import ma.kulha.offer.application.OfferSnapshot;
import ma.kulha.notification.application.NotificationOutbox;
import ma.kulha.reservation.domain.model.Reservation;
import ma.kulha.reservation.domain.model.ReservationCancellationReason;
import ma.kulha.reservation.domain.model.ReservationFilter;
import ma.kulha.reservation.domain.model.ReservationStatus;
import ma.kulha.reservation.domain.repository.ReservationRepository;
import ma.kulha.reservation.domain.repository.ReservationIdempotencyRepository;
import ma.kulha.reservation.interfaces.dto.CancelReservationRequest;
import ma.kulha.reservation.interfaces.dto.CollectReservationRequest;
import ma.kulha.reservation.interfaces.dto.CreateReservationRequest;
import ma.kulha.reservation.interfaces.dto.ReservationAvailabilityResponse;
import ma.kulha.reservation.interfaces.dto.ReservationAvailabilityResponse.PickupSlot;
import ma.kulha.reservation.interfaces.dto.ReservationResponse;
import ma.kulha.shared.web.PageResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class ReservationService {
    private static final String QR_PREFIX = "KULHA1:";
    private final ReservationRepository reservations;
    private final ReservationIdempotencyRepository reservationIdempotency;
    private final ObjectMapper objectMapper;
    private final OfferApi offers;
    private final MerchantApi merchants;
    private final ReservationReferenceGenerator references;
    private final NotificationOutbox notificationOutbox;
    private final Clock clock;
    private final int maximumQuantity;
    ReservationService(ReservationRepository reservations, ReservationIdempotencyRepository reservationIdempotency,
            ObjectMapper objectMapper, OfferApi offers, MerchantApi merchants,
            ReservationReferenceGenerator references, NotificationOutbox notificationOutbox, Clock clock,
            @Value("${app.reservation.max-quantity-per-customer:3}") int maximumQuantity) {
        this.reservations = reservations; this.reservationIdempotency = reservationIdempotency;
        this.objectMapper = objectMapper; this.offers = offers; this.merchants = merchants;
        this.references = references; this.notificationOutbox = notificationOutbox; this.clock = clock;
        if (maximumQuantity < 1 || maximumQuantity > 20)
            throw new IllegalArgumentException("app.reservation.max-quantity-per-customer must be 1..20");
        this.maximumQuantity = maximumQuantity;
    }
    @Transactional(readOnly = true)
    public ReservationAvailabilityResponse availability(UUID offerId, UUID merchantId, Instant from, Instant to, int quantity) {
        if (quantity < 1 || quantity > 500)
            throw new InvalidReservationRequestException("quantity must be between 1 and 500.");
        if (from != null && to != null && !to.isAfter(from))
            throw new InvalidReservationRequestException("The requested date window is invalid.");
        if (from != null && to != null && ChronoUnit.DAYS.between(from, to) > 31)
            throw new InvalidReservationRequestException("The requested date window cannot exceed 31 days.");
        OfferSnapshot offer = offers.snapshot(offerId).orElse(null);
        if (offer == null || offer.pickupStart() == null || offer.pickupEnd() == null
                || !offer.published() || (merchantId != null && !merchantId.equals(offer.merchantId())))
            return unavailable(offerId, quantity);
        Instant now = clock.instant();
        boolean intersects = (from == null || offer.pickupEnd().isAfter(from))
                && (to == null || offer.pickupStart().isBefore(to));
        boolean reservable = intersects && offer.pickupStart().isAfter(now)
                && offer.availableUnits() > 0 && offers.isReservable(offerId, now);
        int max = reservable ? Math.min(maximumQuantity, offer.availableUnits()) : 0;
        Integer total = reservable && quantity <= max ? Math.multiplyExact(offer.priceMinor(), quantity) : null;
        Integer originalTotal = total != null && offer.originalPriceMinor() != null
                ? Math.multiplyExact(offer.originalPriceMinor(), quantity) : null;
        Integer savings = originalTotal != null ? originalTotal - total : null;
        return new ReservationAvailabilityResponse(offerId, offer.title(), offer.category(), offer.merchantId(),
                offer.merchantName(), offer.merchantAddress(), offer.merchantCity(),
                offer.merchantPickupInstructions(), Math.max(0, offer.availableUnits()), max, quantity,
                offer.priceMinor(), offer.originalPriceMinor(), total, originalTotal, savings, "MAD",
                reservable ? List.of(new PickupSlot(offer.pickupStart(), offer.pickupEnd(), offer.availableUnits())) : List.of());
    }
    @Transactional
    public ReservationResponse create(UUID customerId, CreateReservationRequest request, Locale locale,
            String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128)
            throw new InvalidReservationRequestException("A non-empty Idempotency-Key of at most 128 characters is required.");
        String key = idempotencyKey.trim();
        String requestHash = reservationRequestHash(request);
        reservationIdempotency.lock(customerId, key);
        var previous = reservationIdempotency.find(customerId, key);
        if (previous.isPresent()) {
            if (!MessageDigest.isEqual(previous.get().requestHash().getBytes(StandardCharsets.US_ASCII),
                    requestHash.getBytes(StandardCharsets.US_ASCII))) {
                throw new ReservationConflictException("Idempotency-Key was already used with a different reservation request.");
            }
            try {
                return objectMapper.readValue(previous.get().responseSnapshot(), ReservationResponse.class);
            } catch (JacksonException invalidSnapshot) {
                throw new IllegalStateException("Stored reservation idempotency response cannot be read.", invalidSnapshot);
            }
        }
        if (request.priceMinor() != null || request.totalPriceMinor() != null)
            throw new InvalidReservationRequestException("Prices are calculated by the server and must not be sent by the client.");
        Instant now = clock.instant();
        OfferSnapshot before = offers.snapshot(request.offerId())
                .orElseThrow(() -> new ReservationUnavailableException("This basket is no longer available."));
        if (!before.published() || before.pickupStart() == null || before.pickupEnd() == null
                || !before.pickupStart().isAfter(now) || !offers.isReservable(request.offerId(), now))
            throw new ReservationUnavailableException("This pickup window is no longer available.");
        if (!before.pickupStart().equals(request.pickupStart()) || !before.pickupEnd().equals(request.pickupEnd()))
            throw new ReservationConflictException("The selected pickup window has changed. Refresh availability.");
        int allowed = Math.min(maximumQuantity, before.availableUnits());
        if (request.quantity() > allowed)
            throw new ReservationConflictException("The requested quantity exceeds the currently available limit.");
        if (!offers.tryHoldUnits(request.offerId(), request.quantity()))
            throw new ReservationConflictException("This basket no longer has enough available quantity.");
        OfferSnapshot locked = offers.snapshot(request.offerId())
                .orElseThrow(() -> new ReservationUnavailableException("This basket is no longer available."));
        if (!locked.pickupStart().equals(request.pickupStart()) || !locked.pickupEnd().equals(request.pickupEnd())
                || locked.priceMinor() != before.priceMinor() || !locked.merchantId().equals(before.merchantId()))
            throw new ReservationConflictException("The basket changed while it was being reserved. Please retry.");
        String pickupCode = references.pickupCode();
        Reservation pending = Reservation.pending(UUID.randomUUID(), references.reference(pickupCode), customerId,
                locked.offerId(), locked.merchantId(), locked.title(), locked.category(), locked.merchantName(),
                locked.merchantAddress(), locked.merchantCity(), locked.merchantPickupInstructions(),
                locked.pickupStart(), locked.pickupEnd(), pickupCode, request.quantity(), locked.priceMinor(), now);
        Reservation confirmed = pending.confirm(now);
        reservations.insert(confirmed);
        notificationOutbox.enqueue(customerId, "RESERVATION_CONFIRMED",
                localized(locale, "Réservation confirmée", "Reservation confirmed", "تم تأكيد الحجز"),
                localized(locale, "Votre panier est réservé. Consultez votre créneau de retrait.",
                        "Your basket is booked. Check your pickup window.",
                        "تم حجز سلتك. تحقق من موعد الاستلام."),
                localized(locale, "Voir la réservation", "View reservation", "عرض الحجز"),
                "/reservations/" + confirmed.reference());
        String qrPayload = issuePickupCredential(confirmed, now);
        ReservationResponse response = ReservationResponse.from(confirmed, locale, now, qrPayload);
        try {
            reservationIdempotency.save(customerId, key, requestHash, confirmed.id(),
                    objectMapper.writeValueAsString(response));
        } catch (JacksonException serializationFailure) {
            throw new IllegalStateException("Reservation response could not be persisted for idempotent replay.",
                    serializationFailure);
        }
        return response;
    }

    private static String reservationRequestHash(CreateReservationRequest request) {
        String canonical = String.join("\n",
                request.offerId().toString(),
                request.quantity().toString(),
                request.pickupStart().toString(),
                request.pickupEnd().toString(),
                String.valueOf(request.priceMinor()),
                String.valueOf(request.totalPriceMinor()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }
    @Transactional
    public ReservationResponse get(String reference, UUID userId, boolean admin, boolean merchantStaff, Locale locale) {
        Instant now = clock.instant();
        Reservation reservation = reservations.findByReferenceForUpdate(reference).orElseThrow(ReservationNotFoundException::new);
        if ((reservation.status() == ReservationStatus.PENDING || reservation.status() == ReservationStatus.CONFIRMED)
                && !reservation.pickupEnd().isAfter(now)) {
            Reservation expired = reservation.expire(now);
            if (expired.status() != reservation.status()) {
                reservations.updateStatus(expired, reservation.status());
                reservation = expired;
            }
        }
        boolean owner = reservation.customerId().equals(userId);
        boolean authorizedMerchant = merchantStaff && merchants.resolveActingMerchant(userId)
                .filter(reservation.merchantId()::equals).isPresent();
        if (!owner && !admin && !authorizedMerchant) throw new ReservationNotFoundException();
        String qrPayload = owner ? issuePickupCredential(reservation, now) : null;
        return ReservationResponse.from(reservation, locale, now, qrPayload);
    }
    @Transactional
    public PageResponse<ReservationResponse> list(UUID customerId, String status, int page, int size, Locale locale) {
        if (page < 0 || size < 1) throw new InvalidReservationRequestException("page must be non-negative and size must be positive.");
        int safeSize = Math.min(size, 50);
        if (page > Integer.MAX_VALUE / safeSize) throw new InvalidReservationRequestException("page is too large.");
        Instant now = clock.instant();
        reservations.expireActive(now);
        ReservationFilter filter = ReservationFilter.parse(status);
        List<ReservationResponse> items = reservations.list(customerId, filter, now, safeSize, page * safeSize)
                .stream().map(reservation -> ReservationResponse.from(reservation, locale, now)).toList();
        return new PageResponse<>(items, page, safeSize, reservations.count(customerId, filter, now));
    }
    @Transactional
    public ReservationResponse cancel(String reference, UUID customerId, CancelReservationRequest request, Locale locale) {
        Instant now = clock.instant();
        Reservation reservation = reservations.findByReferenceForUpdate(reference).orElseThrow(ReservationNotFoundException::new);
        if (!reservation.customerId().equals(customerId)) throw new ReservationNotFoundException();
        ReservationCancellationReason reason;
        try {
            reason = ReservationCancellationReason.parse(request == null ? null : request.reasonCode());
        } catch (IllegalArgumentException invalidReason) {
            throw new InvalidReservationRequestException("Unsupported cancellation reason.");
        }
        String comment = request == null || request.comment() == null ? null : request.comment().trim();
        if (comment != null && comment.isEmpty()) comment = null;
        Reservation cancelled = reservation.cancel(now);
        if (!reservations.updateStatus(cancelled, reservation.status()))
            throw new ReservationConflictException("The reservation changed. Refresh and try again.");
        reservations.recordCancellationEvent(reservation, reason.name(), comment, customerId, now);
        offers.releaseUnits(reservation.offerId(), reservation.quantity());
        reservations.markPickupCredentialsUsed(reservation.id(), now);
        notificationOutbox.enqueue(customerId, "RESERVATION_CANCELLED",
                localized(locale, "Réservation annulée", "Reservation cancelled", "تم إلغاء الحجز"),
                localized(locale, "Votre réservation a été annulée.", "Your reservation has been cancelled.", "تم إلغاء حجزك."),
                localized(locale, "Trouver un autre panier", "Find another basket", "ابحث عن سلة أخرى"),
                "/offers");
        return ReservationResponse.from(cancelled, locale, now);
    }
    @Transactional
    public ma.kulha.reservation.interfaces.dto.PickupPreviewResponse previewPickup(
            CollectReservationRequest request, UUID merchantUserId) {
        Instant now = clock.instant();
        if (request == null) throw new InvalidReservationRequestException("A QR token or pickup code is required.");
        UUID merchantId = merchants.resolveActingMerchant(merchantUserId)
                .orElseThrow(ReservationNotFoundException::new);
        if (!merchants.isApproved(merchantId)
                || !merchants.can(merchantUserId, merchantId, "VALIDATE_PICKUPS")) {
            throw new ReservationNotFoundException();
        }
        Reservation reservation;
        if (request.qrPayload() != null && !request.qrPayload().isBlank()) {
            String payload = request.qrPayload().trim();
            if (!payload.startsWith(QR_PREFIX) || payload.length() > 100)
                throw new InvalidReservationRequestException("The QR code is invalid.");
            String token = payload.substring(QR_PREFIX.length());
            if (token.length() < 40 || token.length() > 60)
                throw new InvalidReservationRequestException("The QR code is invalid.");
            String tokenHash = ReservationReferenceGenerator.hashPickupQrToken(token);
            reservation = reservations.findByPickupCredentialHashForUpdate(tokenHash, now)
                    .orElseThrow(ReservationNotFoundException::new);
        } else {
            if (request.reference() == null || request.pickupCode() == null || request.pickupCode().isBlank())
                throw new InvalidReservationRequestException("Enter the reservation reference and pickup code.");
            reservation = reservations.findByReferenceForUpdate(request.reference().trim())
                    .orElseThrow(ReservationNotFoundException::new);
            if (!MessageDigest.isEqual(reservation.pickupCode().getBytes(StandardCharsets.UTF_8),
                    request.pickupCode().trim().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
                throw new ReservationNotFoundException();
            }
        }
        if (!reservation.merchantId().equals(merchantId)) throw new ReservationNotFoundException();
        String validationState;
        boolean canConfirm = false;
        if (reservation.status() == ReservationStatus.COMPLETED) {
            validationState = "ALREADY_COLLECTED";
        } else if (reservation.status() != ReservationStatus.CONFIRMED) {
            validationState = "NOT_VALID";
        } else if (now.isBefore(reservation.pickupStart())) {
            validationState = "WINDOW_NOT_OPEN";
        } else if (!now.isBefore(reservation.pickupEnd())) {
            validationState = "WINDOW_ENDED";
        } else {
            validationState = "VALID";
            canConfirm = true;
        }
        return new ma.kulha.reservation.interfaces.dto.PickupPreviewResponse(
                reservation.reference(), reservation.merchantName(), reservation.offerTitle(),
                reservation.quantity(), reservation.totalPriceMinor(), "MAD",
                reservation.pickupStart(), reservation.pickupEnd(), reservation.status().name(),
                validationState, canConfirm);
    }

    @Transactional
    public ReservationResponse collect(CollectReservationRequest request, UUID merchantUserId, Locale locale) {
        Instant now = clock.instant();
        if (request == null) throw new InvalidReservationRequestException("A QR token or pickup code is required.");
        UUID merchantId = merchants.resolveActingMerchant(merchantUserId)
                .orElseThrow(ReservationNotFoundException::new);
        if (!merchants.isApproved(merchantId)
                || !merchants.can(merchantUserId, merchantId, "VALIDATE_PICKUPS")) {
            throw new ReservationNotFoundException();
        }
        Reservation reservation;
        if (request.qrPayload() != null && !request.qrPayload().isBlank()) {
            String payload = request.qrPayload().trim();
            if (!payload.startsWith(QR_PREFIX) || payload.length() > 100)
                throw new InvalidReservationRequestException("The QR code is invalid.");
            String token = payload.substring(QR_PREFIX.length());
            if (token.length() < 40 || token.length() > 60)
                throw new InvalidReservationRequestException("The QR code is invalid.");
            String tokenHash = ReservationReferenceGenerator.hashPickupQrToken(token);
            reservation = reservations.findByPickupCredentialHashForUpdate(tokenHash, now)
                    .orElseThrow(ReservationNotFoundException::new);
        } else {
            if (request.reference() == null || request.pickupCode() == null || request.pickupCode().isBlank())
                throw new InvalidReservationRequestException("Enter the reservation reference and pickup code.");
            reservation = reservations.findByReferenceForUpdate(request.reference().trim())
                    .orElseThrow(ReservationNotFoundException::new);
            if (!MessageDigest.isEqual(reservation.pickupCode().getBytes(StandardCharsets.UTF_8),
                    request.pickupCode().trim().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)))
                throw new ReservationNotFoundException();
        }
        if (!reservation.merchantId().equals(merchantId)) throw new ReservationNotFoundException();
        if (reservation.status() != ReservationStatus.CONFIRMED)
            throw new ReservationConflictException("This reservation has already been collected, cancelled or expired.");
        if (now.isBefore(reservation.pickupStart()))
            throw new ReservationConflictException("The pickup window has not started yet.");
        if (!now.isBefore(reservation.pickupEnd())) {
            Reservation expired = reservation.expire(now);
            if (!reservations.updateStatus(expired, reservation.status()))
                throw new ReservationConflictException("The reservation changed while it was being expired.");
            reservations.markPickupCredentialsUsed(reservation.id(), now);
            notificationOutbox.enqueue(reservation.customerId(), "RESERVATION_EXPIRED",
                    localized(locale, "Réservation expirée", "Reservation expired", "انتهى الحجز"),
                    localized(locale, "Le créneau de retrait est terminé.", "The pickup window has ended.", "انتهى موعد الاستلام."),
                    localized(locale, "Découvrir les paniers", "Explore baskets", "استكشف السلال"),
                    "/offers");
            return ReservationResponse.from(expired, locale, now);
        }
        Reservation completed = reservation.complete(now);
        if (!reservations.updateStatus(completed, reservation.status()))
            throw new ReservationConflictException("The reservation changed while it was being collected.");
        reservations.markPickupCredentialsUsed(reservation.id(), now);
        notificationOutbox.enqueue(reservation.customerId(), "RESERVATION_COLLECTED",
                localized(locale, "Panier récupéré", "Basket collected", "تم استلام السلة"),
                localized(locale, "Votre retrait est confirmé. Merci de lutter contre le gaspillage !",
                        "Your pickup is confirmed. Thanks for helping reduce food waste!", "تم تأكيد الاستلام. شكراً لمساهمتك في الحد من هدر الطعام!"),
                null, null);
        return ReservationResponse.from(completed, locale, now);
    }
    private static String localized(Locale locale, String french, String english, String arabic) {
        return switch (locale == null ? "fr" : locale.getLanguage()) {
            case "en" -> english;
            case "ar" -> arabic;
            default -> french;
        };
    }

    private String issuePickupCredential(Reservation reservation, Instant now) {
        if (reservation.status() != ReservationStatus.CONFIRMED || !reservation.pickupEnd().isAfter(now)) return null;
        String token = references.pickupQrToken();
        reservations.insertPickupCredential(reservation.id(),
                ReservationReferenceGenerator.hashPickupQrToken(token), reservation.pickupEnd(), now);
        return QR_PREFIX + token;
    }
    private static ReservationAvailabilityResponse unavailable(UUID offerId, int quantity) {
        return new ReservationAvailabilityResponse(offerId, null, null, null, null, null, null, null,
                0, 0, quantity, null, null, null, null, null, "MAD", List.of());
    }
}
