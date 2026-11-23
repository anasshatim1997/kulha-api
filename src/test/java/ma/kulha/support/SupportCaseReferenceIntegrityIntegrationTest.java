package ma.kulha.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.kulha.admin.support.application.SupportCaseService;
import ma.kulha.admin.support.interfaces.dto.OpenSupportCaseRequest;
import ma.kulha.identity.domain.model.Role;
import ma.kulha.identity.domain.model.User;
import ma.kulha.merchant.application.MerchantService;
import ma.kulha.merchant.domain.model.BusinessType;
import ma.kulha.merchant.domain.model.Merchant;
import ma.kulha.offer.application.OfferApplicationService;
import ma.kulha.offer.domain.model.AllergensStatus;
import ma.kulha.offer.domain.model.OfferCategory;
import ma.kulha.reservation.domain.model.Reservation;
import ma.kulha.reservation.domain.repository.ReservationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

class SupportCaseReferenceIntegrityIntegrationTest extends AbstractApiIntegrationTest {
    @Autowired MerchantService merchantService;
    @Autowired OfferApplicationService offerService;
    @Autowired ReservationRepository reservationRepository;
    @Autowired SupportCaseService supportCases;

    @Test
    void rejectsReservationWhoseOfferBelongsToAnotherMerchant() {
        User admin = createUser("support-ref-admin@example.com", true, Role.ADMIN);
        User customer = createUser("support-ref-customer@example.com", true);
        User ownerA = createUser("support-ref-owner-a@example.com", true);
        User ownerB = createUser("support-ref-owner-b@example.com", true);
        Merchant merchantA = approvedMerchant(admin, ownerA, "Reference Bakery A", "+212600000093");
        Merchant merchantB = approvedMerchant(admin, ownerB, "Reference Bakery B", "+212600000094");
        var offer = offerService.create(ownerA.id(), "Reference Basket", "Description", OfferCategory.OTHER,
                500, null, 2, Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200),
                AllergensStatus.UNKNOWN, List.of(), null);
        offerService.publish(ownerA.id(), offer.id());

        String reservationReference = "KLH-R-MISMATCH";
        Instant now = Instant.now();
        reservationRepository.insert(Reservation.pending(UUID.randomUUID(), reservationReference, customer.id(),
                offer.id(), merchantB.id(), "Reference Basket", "OTHER", merchantB.name(),
                merchantB.address(), merchantB.city(), null, now.plusSeconds(3600), now.plusSeconds(7200),
                "REFMISMATCH", 1, 500, now));

        assertThatThrownBy(() -> supportCases.open(customer.id(), request(reservationReference)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(409));
        assertThat(jdbc.sql("SELECT count(*) FROM admin.support_cases WHERE reservation_reference=:reference")
                .param("reference", reservationReference).query(Long.class).single()).isZero();
    }

    @Test
    void doesNotAllowCustomerToOpenCaseAgainstAnotherUsersReservation() {
        User customer = createUser("support-ref-owner-customer@example.com", true);
        User otherCustomer = createUser("support-ref-other-customer@example.com", true);
        User admin = createUser("support-ref-owner-admin@example.com", true, Role.ADMIN);
        User owner = createUser("support-ref-reservation-owner@example.com", true);
        Merchant merchant = approvedMerchant(admin, owner, "Owned Reservation Bakery", "+212600000095");
        var offer = offerService.create(owner.id(), "Owned Reservation Basket", "Description", OfferCategory.OTHER,
                500, null, 2, Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200),
                AllergensStatus.UNKNOWN, List.of(), null);
        offerService.publish(owner.id(), offer.id());
        String reservationReference = "KLH-R-OWNERSHIP";
        Instant now = Instant.now();
        reservationRepository.insert(Reservation.pending(UUID.randomUUID(), reservationReference, otherCustomer.id(),
                offer.id(), merchant.id(), "Owned Reservation Basket", "OTHER", merchant.name(),
                merchant.address(), merchant.city(), null, now.plusSeconds(3600), now.plusSeconds(7200),
                "REFOWNER", 1, 500, now));

        assertThatThrownBy(() -> supportCases.open(customer.id(), request(reservationReference)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(404));
        assertThat(jdbc.sql("SELECT count(*) FROM admin.support_cases WHERE reservation_reference=:reference")
                .param("reference", reservationReference).query(Long.class).single()).isZero();
    }

    private Merchant approvedMerchant(User admin, User owner, String name, String phone) {
        Merchant merchant = merchantService.apply(owner.id(), new Merchant.Profile(name, BusinessType.BAKERY,
                "12 rue Atlas", "Casablanca", phone, "Lun-Sam 8h-18h"));
        merchantService.approve(admin.id(), merchant.id());
        return merchant;
    }

    private OpenSupportCaseRequest request(String reservationReference) {
        return new OpenSupportCaseRequest("FOOD_SAFETY", "Food safety concern",
                "A customer reports an issue with the basket.", reservationReference);
    }
}
