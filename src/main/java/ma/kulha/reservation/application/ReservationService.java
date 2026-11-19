package ma.kulha.reservation.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import ma.kulha.merchant.application.MerchantApi;
import ma.kulha.offer.application.OfferApi;
import ma.kulha.offer.application.OfferSnapshot;
import ma.kulha.reservation.domain.model.CancellationReasonCode;
import ma.kulha.reservation.domain.model.Reservation;
import ma.kulha.reservation.domain.model.ReservationFilter;
import ma.kulha.reservation.domain.model.ReservationStatus;
import ma.kulha.reservation.domain.repository.ReservationRepository;
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
    private final ReservationRepository reservations;
    private final OfferApi offers;
    private final MerchantApi merchants;
    private final ReservationReferenceGenerator references;
    private final PickupCredentialGenerator pickupCredentials;
    private final Clock clock;
    private final int maximumQuantity;

    ReservationService(ReservationRepository reservations, OfferApi offers, MerchantApi merchants,
            ReservationReferenceGenerator references, PickupCredentialGenerator pickupCredentials, Clock clock,
            @Value("${app.reservation.max-quantity-per-customer:3}") int maximumQuantity) {
        this.reservations = reservations;
        this.offers = offers;
        this.merchants = merchants;
        this.references = references;
        this.pickupCredentials = pickupCredentials;
        this.clock = clock;
        if (maximumQuantity < 1 || maximumQuantity > 20) {
            throw new IllegalArgumentException("app.reservation.max-quantity-per-customer must be 1..20");
        }
        this.maximumQuantity = maximumQuantity;
    }

    @Transactional(readOnly = true)
    public ReservationAvailabilityResponse availability(UUID offerId, UUID merchantId, Instant from, Instant to, int quantity) {
        if (quantity < 1 || quantity > 500) {
            throw new InvalidReservationRequestException("quantity must be between 1 and 500.");
        }
        if (from != null && to != null && !to.isAfter(from)) {
            throw new InvalidReservationRequestException("The requested date window is invalid.");
        }
        if (from != null && to != null && ChronoUnit.DAYS.between(from, to) > 31) {
            throw new InvalidReservationRequestException("The requested date window cannot exceed 31 days.");
        }
        OfferSnapshot offer = offers.snapshot(offerId).orElse(null);
        if (offer == null || offer.pickupStart() == null || offer.pickupEnd() == null
                || !offer.published() || (merchantId != null && !merchantId.equals(offer.merchantId()))) {
            return unavailable(offerId, quantity);
        }
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
    public ReservationResponse create(UUID customerId, CreateReservationRequest request, Locale locale) {
        if (request.priceMinor() != null || request.totalPriceMinor() != null) {
            throw new InvalidReservationRequestException("Prices are calculated by the server and must not be sent by the client.");
        }
        Instant now = clock.instant();
        OfferSnapshot before = offers.snapshot(request.offerId())
                .orElseThrow(() -> new ReservationUnavailableException("This basket is no longer available."));
        if (!before.published() || before.pickupStart() == null || before.pickupEnd() == null
                || !before.pickupStart().isAfter(now) || !offers.isReservable(request.offerId(), now)) {
            throw new ReservationUnavailableException("This pickup window is no longer available.");
        }
        if (!before.pickupStart().equals(request.pickupStart()) || !before.pickupEnd().equals(request.pickupEnd())) {
            throw new ReservationConflictException("The selected pickup window has changed. Refresh availability.");
        }
        int allowed = Math.min(maximumQuantity, before.availableUnits());
        if (request.quantity() > allowed) {
            throw new ReservationConflictException("The requested quantity exceeds the currently available limit.");
        }
        if (!offers.tryHoldUnits(request.offerId(), request.quantity())) {
            throw new ReservationConflictException("This basket no longer has enough available quantity.");
        }
        OfferSnapshot locked = offers.snapshot(request.offerId())
                .orElseThrow(() -> new ReservationUnavailableException("This basket is no longer available."));
        if (!locked.pickupStart().equals(request.pickupStart()) || !locked.pickupEnd().equals(request.pickupEnd())
                || locked.priceMinor() != before.priceMinor() || !locked.merchantId().equals(before.merchantId())) {
            throw new ReservationConflictException("The basket changed while it was being reserved. Please retry.");
        }
        String pickupCode = references.pickupCode();
        Reservation pending = Reservation.pending(UUID.randomUUID(), references.reference(pickupCode), customerId,
                locked.offerId(), locked.merchantId(), locked.title(), locked.category(), locked.merchantName(),
                locked.merchantAddress(), locked.merchantCity(), locked.merchantPickupInstructions(),
                locked.pickupStart(), locked.pickupEnd(), pickupCode, request.quantity(), locked.priceMinor(), now);
        Reservation confirmed = pending.confirm(now);
        reservations.insert(confirmed);
        return ReservationResponse.from(confirmed, locale, now);
    }

    @Transactional
    public ReservationResponse get(String reference, UUID userId, boolean admin, boolean merchantStaff, Locale locale) {
        Instant now = clock.instant();
        Reservation reservation = reservations.findByReferenceForUpdate(reference).orElseThrow(ReservationNotFoundException::new);
        expireIfPast(reservation, now);
        boolean owner = reservation.customerId().equals(userId);
        boolean authorizedMerchant = merchantStaff && merchants.resolveActingMerchant(userId)
                .filter(reservation.merchantId()::equals).isPresent();
        if (!owner && !admin && !authorizedMerchant) throw new ReservationNotFoundException();
        return ReservationResponse.from(reservation, locale, now);
    }

    @Transactional
    public PickupCredential issuePickupCredential(String reference, UUID customerId) {
        Instant now = clock.instant();
        Reservation reservation = reservations.findByReferenceForUpdate(reference)
                .orElseThrow(ReservationNotFoundException::new);
        if (!reservation.customerId().equals(customerId)) throw new ReservationNotFoundException();
        if (reservation.status() != ReservationStatus.CONFIRMED || !reservation.pickupEnd().isAfter(now)) {
            throw new InvalidReservationStateException("A pickup QR code is only available for an active confirmed reservation.");
        }
        String token = pickupCredentials.generate();
        if (!reservations.savePickupCredential(reservation.id(), pickupCredentials.hash(token), now)) {
            throw new ReservationConflictException("The pickup credential could not be issued. Refresh the reservation.");
        }
        // The credential is single-use and can be refreshed by the owner until the pickup window ends.
        return new PickupCredential(token, reservation.pickupEnd());
    }

    @Transactional
    public ReservationResponse collect(CollectReservationRequest request, UUID merchantStaffId, Locale locale) {
        request.validateCredential();
        UUID actingMerchant = merchants.resolveActingMerchant(merchantStaffId).orElseThrow(ReservationNotFoundException::new);
        Instant now = clock.instant();
        Reservation reservation;
        if (request.token() != null && !request.token().isBlank()) {
            String token = request.token().trim();
            if (token.startsWith("KULHA-PICKUP:")) token = token.substring("KULHA-PICKUP:".length());
            reservation = reservations.findByPickupCredentialHashForUpdate(pickupCredentials.hash(token))
                    .orElseThrow(ReservationNotFoundException::new);
        } else {
            reservation = reservations.findByReferenceForUpdate(request.reference().trim())
                    .orElseThrow(ReservationNotFoundException::new);
            if (!pickupCredentials.matchesCode(request.pickupCode(), reservation.pickupCode())) {
                throw new ReservationNotFoundException();
            }
        }
        if (!reservation.merchantId().equals(actingMerchant)) throw new ReservationNotFoundException();
        if (reservation.status() != ReservationStatus.CONFIRMED || now.isBefore(reservation.pickupStart())
                || !reservation.pickupEnd().isAfter(now)) {
            throw new InvalidReservationStateException("This reservation is not collectable in the current pickup window.");
        }
        Reservation completed = reservation.complete(now);
        if (!reservations.updateStatus(completed, reservation.status())) {
            throw new ReservationConflictException("This reservation has already been collected or changed.");
        }
        return ReservationResponse.from(completed, locale, now);
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
    public ReservationResponse cancel(String reference, UUID customerId, String reason, String comment, Locale locale) {
        Instant now = clock.instant();
        Reservation reservation = reservations.findByReferenceForUpdate(reference)
                .orElseThrow(ReservationNotFoundException::new);
        if (!reservation.customerId().equals(customerId)) throw new ReservationNotFoundException();
        Reservation cancelled = reservation.cancel(now);
        CancellationReasonCode reasonCode = CancellationReasonCode.parse(reason);
        String cleanComment = comment == null || comment.isBlank() ? null : comment.trim();
        if (cleanComment != null && cleanComment.length() > 500) {
            throw new InvalidReservationRequestException("Cancellation comment cannot exceed 500 characters.");
        }
        if (!reservations.updateStatus(cancelled, reservation.status())) {
            throw new ReservationConflictException("The reservation changed. Refresh and try again.");
        }
        reservations.recordCancellation(reservation, reasonCode.name(), cleanComment, now);
        offers.releaseUnits(reservation.offerId(), reservation.quantity());
        return ReservationResponse.from(cancelled, locale, now);
    }

    private void expireIfPast(Reservation reservation, Instant now) {
        if ((reservation.status() == ReservationStatus.PENDING || reservation.status() == ReservationStatus.CONFIRMED)
                && !reservation.pickupEnd().isAfter(now)) {
            Reservation expired = reservation.expire(now);
            reservations.updateStatus(expired, reservation.status());
        }
    }

    private static ReservationAvailabilityResponse unavailable(UUID offerId, int quantity) {
        return new ReservationAvailabilityResponse(offerId, null, null, null, null, null, null, null,
                0, 0, quantity, null, null, null, null, null, "MAD", List.of());
    }
}
