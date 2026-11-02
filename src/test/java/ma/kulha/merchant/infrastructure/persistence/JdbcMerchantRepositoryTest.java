package ma.kulha.merchant.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import ma.kulha.merchant.domain.model.BusinessType;
import ma.kulha.merchant.domain.model.Merchant;
import ma.kulha.merchant.domain.model.MerchantStatus;
import ma.kulha.merchant.domain.repository.MerchantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class JdbcMerchantRepositoryTest {

    @Autowired
    MerchantRepository merchants;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void cleanTable() {
        jdbc.sql("DELETE FROM merchant.merchants").update();
    }

    @Test
    void updateWithExpectedStatusReturnsTrueAndChangesTheRow() {
        Merchant original = merchant("Original Bakery");
        merchants.insert(original);
        Merchant updated = original.withProfile(profile("Updated Bakery"), Instant.now());

        assertThat(merchants.updateIfStatus(updated, MerchantStatus.PENDING)).isTrue();
        assertThat(merchants.findById(original.id())).get()
                .extracting(Merchant::name).isEqualTo("Updated Bakery");
    }

    @Test
    void updateWithStaleExpectedStatusReturnsFalseAndLeavesTheRowUnchanged() {
        Merchant original = merchant("Original Bakery");
        merchants.insert(original);
        Merchant updated = original.withProfile(profile("Updated Bakery"), Instant.now());

        assertThat(merchants.updateIfStatus(updated, MerchantStatus.REJECTED)).isFalse();
        assertThat(merchants.findById(original.id())).get()
                .extracting(Merchant::name).isEqualTo("Original Bakery");
    }

    private static Merchant merchant(String name) {
        return Merchant.apply(UUID.randomUUID(), profile(name), Instant.now());
    }

    private static Merchant.Profile profile(String name) {
        return new Merchant.Profile(name, BusinessType.BAKERY, "12 rue des Fleurs",
                "Casablanca", "+212600000000", "Lun-Sam 7h-20h");
    }
}
