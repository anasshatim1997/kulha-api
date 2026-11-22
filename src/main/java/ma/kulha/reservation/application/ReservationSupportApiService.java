package ma.kulha.reservation.application;

import java.util.Optional;
import java.util.Collection;
import java.util.List;
import ma.kulha.reservation.domain.repository.ReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReservationSupportApiService implements ReservationSupportApi {
    private final ReservationRepository reservations;

    ReservationSupportApiService(ReservationRepository reservations) {
        this.reservations = reservations;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReservationReference> findByReference(String reference) {
        return reservations.findByReference(reference).map(reservation -> new ReservationReference(
                reservation.reference(), reservation.customerId(), reservation.merchantId(), reservation.offerId(),
                reservation.offerTitle(), reservation.merchantName()));

    }

    @Override
    @Transactional(readOnly = true)
    public List<ReservationReference> findByReferences(Collection<String> references) {
        return reservations.findByReferences(references).stream().map(reservation -> new ReservationReference(
                reservation.reference(), reservation.customerId(), reservation.merchantId(), reservation.offerId(),
                reservation.offerTitle(), reservation.merchantName())).toList();
    }
}
