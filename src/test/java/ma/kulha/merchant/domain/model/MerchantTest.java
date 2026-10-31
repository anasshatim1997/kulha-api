package ma.kulha.merchant.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MerchantTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-10-30T11:00:00Z");
    private final UUID owner = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    private static Merchant.Profile profile(String name) {
        return new Merchant.Profile(name, BusinessType.BAKERY, "12 rue des Fleurs", "Casablanca",
                "+212600000000", "Lun-Sam 7h-20h");
    }

    private Merchant pending() {
        return Merchant.apply(owner, profile("Boulangerie Atlas"), NOW);
    }

    @Test
    void applyCreatesAPendingMerchantWithoutADecision() {
        Merchant merchant = pending();

        assertThat(merchant.status()).isEqualTo(MerchantStatus.PENDING);
        assertThat(merchant.isApproved()).isFalse();
        assertThat(merchant.decidedBy()).isNull();
        assertThat(merchant.decisionReason()).isNull();
    }

    @Test
    void approvingAPendingMerchantRecordsWhoAndWhen() {
        Merchant approved = pending().approve(admin, LATER);

        assertThat(approved.status()).isEqualTo(MerchantStatus.APPROVED);
        assertThat(approved.isApproved()).isTrue();
        assertThat(approved.decidedBy()).isEqualTo(admin);
        assertThat(approved.decidedAt()).isEqualTo(LATER);
    }

    @Test
    void anApprovedOrRejectedMerchantCannotBeApprovedAgain() {
        Merchant approved = pending().approve(admin, LATER);
        Merchant rejected = pending().reject(admin, "Adresse introuvable", LATER);

        assertThatThrownBy(() -> approved.approve(admin, LATER)).isInstanceOf(InvalidMerchantStateException.class);
        assertThatThrownBy(() -> rejected.approve(admin, LATER)).isInstanceOf(InvalidMerchantStateException.class);
    }

    @Test
    void rejectNeedsAReasonAndAPendingMerchant() {
        assertThatThrownBy(() -> pending().reject(admin, "  ", LATER)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pending().approve(admin, LATER).reject(admin, "Trop tard", LATER))
                .isInstanceOf(InvalidMerchantStateException.class);

        Merchant rejected = pending().reject(admin, "  Adresse introuvable ", LATER);
        assertThat(rejected.status()).isEqualTo(MerchantStatus.REJECTED);
        assertThat(rejected.decisionReason()).isEqualTo("Adresse introuvable");
    }

    @Test
    void onlyAnApprovedMerchantCanBeSuspendedAndAReasonIsRequired() {
        assertThatThrownBy(() -> pending().suspend(admin, "Plaintes", LATER))
                .isInstanceOf(InvalidMerchantStateException.class);
        Merchant approved = pending().approve(admin, LATER);
        assertThatThrownBy(() -> approved.suspend(admin, "", LATER)).isInstanceOf(IllegalArgumentException.class);

        assertThat(approved.suspend(admin, "Plaintes", LATER).status()).isEqualTo(MerchantStatus.SUSPENDED);
    }

    @Test
    void approvingASuspendedMerchantReinstatesItAndClearsTheReason() {
        Merchant suspended = pending().approve(admin, LATER).suspend(admin, "Plaintes", LATER);

        Merchant reinstated = suspended.approve(admin, LATER);

        assertThat(reinstated.status()).isEqualTo(MerchantStatus.APPROVED);
        assertThat(reinstated.decisionReason()).isNull();
    }

    @Test
    void correctingARejectedProfileResubmitsItAsPending() {
        Merchant rejected = pending().reject(admin, "Adresse introuvable", LATER);

        Merchant resubmitted = rejected.withProfile(profile("Boulangerie Atlas 2"), LATER);

        assertThat(resubmitted.status()).isEqualTo(MerchantStatus.PENDING);
        assertThat(resubmitted.name()).isEqualTo("Boulangerie Atlas 2");
        assertThat(resubmitted.decisionReason()).isNull();
        assertThat(resubmitted.decidedBy()).isNull();
    }

    @Test
    void anApprovedMerchantKeepsItsStatusWhenItEditsItsProfile() {
        Merchant approved = pending().approve(admin, LATER);

        assertThat(approved.withProfile(profile("Nouveau nom"), LATER).status()).isEqualTo(MerchantStatus.APPROVED);
    }

    @Test
    void aSuspendedMerchantCannotEditItsProfile() {
        Merchant suspended = pending().approve(admin, LATER).suspend(admin, "Plaintes", LATER);

        assertThatThrownBy(() -> suspended.withProfile(profile("Autre"), LATER))
                .isInstanceOf(InvalidMerchantStateException.class);
    }

    @Test
    void aProfileRejectsBlankAndTooLongFields() {
        assertThatThrownBy(() -> profile("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> profile("x".repeat(121))).isInstanceOf(IllegalArgumentException.class);
        assertThat(profile("  Boulangerie  ").name()).isEqualTo("Boulangerie");
    }
}
