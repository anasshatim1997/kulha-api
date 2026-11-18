package ma.kulha.offer.infrastructure.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class ElasticsearchOfferSearchIntegrationTest {
    private final String url = System.getenv().getOrDefault(
            "ELASTICSEARCH_INTEGRATION_URL",
            System.getenv().getOrDefault("ELASTICSEARCH_URL", "http://localhost:9200"));
    private final String index = "kulha-offers-test-" + UUID.randomUUID().toString().replace("-", "");
    private final RestClient admin = RestClient.builder().baseUrl(url).build();
    private ElasticsearchOfferSearchAdapter search;
    private UUID offerId;

    @BeforeEach
    void setUp() {
        search = new ElasticsearchOfferSearchAdapter(RestClient.builder(), new ObjectMapper(), url, index, "", "", 0, 2000, 10000, false);
        offerId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        try {
            admin.delete().uri("/{index}", index).retrieve().toBodilessEntity();
        } catch (RuntimeException ignored) {
        }
    }

    @Test
    void nearbySearchFiltersByRadiusAndReturnsActualDistance() {
        Instant now = Instant.now();
        UUID merchantId = UUID.randomUUID();
        UUID closeId = UUID.randomUUID();
        UUID farId = UUID.randomUUID();
        search.index(new OfferSearchDocument(closeId, merchantId, "Close basket", "Nearby bakery",
                "BREAD_PASTRY", 500, 700, 5, 2, now.plusSeconds(3600), now.plusSeconds(7200), now,
                now, "Local bakery", "Casablanca", "Maarif", "Address", null, List.of(), "UNKNOWN", null,
                33.590, -7.620));
        search.index(new OfferSearchDocument(farId, UUID.randomUUID(), "Far basket", "Distant bakery",
                "BREAD_PASTRY", 400, 700, 5, 3, now.plusSeconds(3600), now.plusSeconds(7200), now,
                now, "Far bakery", "Casablanca", "Anfa", "Address", null, List.of(), "UNKNOWN", null,
                33.700, -7.620));

        // Production indexing intentionally relies on Elasticsearch's regular refresh interval.
        // The integration test refreshes explicitly to make its read-after-write assertion deterministic.
        admin.post().uri("/{index}/_refresh", index).retrieve().toBodilessEntity();

        OfferSearchPage nearby = search.search(null, null, null, null, null, null, null,
                true, false, "distance", 0, 10, 33.589, -7.620, 2000, now);

        assertThat(nearby.ids()).containsExactly(closeId);
        assertThat(nearby.distanceMeters().get(closeId)).isBetween(50.0, 300.0);
    }

    @Test
    void indexesAndFindsFrenchArabicAndPrefixSearchableOfferDocuments() {
        Instant now = Instant.now();
        search.index(new OfferSearchDocument(offerId, UUID.randomUUID(), "Msemen maison مسمن منزلي", "Crêpes marocaines",
                "BREAD_PASTRY", 500, 700, 5, 3, now.plusSeconds(3600), now.plusSeconds(7200), now,
                now, "Boulangerie Nour", "Casablanca", "Maarif", "Maarif", "Retrait au comptoir",
                List.of(), "UNKNOWN", null));

        admin.post().uri("/{index}/_refresh", index).retrieve().toBodilessEntity();
        List<OfferSearchDocument> suggestions = search.suggest("mse", "Casablanca", "Maarif", 6, now);
        List<OfferSearchDocument> arabicSuggestions = search.suggest("مس", "Casablanca", "Maarif", 6, now);
        OfferSearchPage results = search.search("msemen", null, null, null, null,
                "Casablanca", "Maarif", true, "pickup_end", 0, 10, now);
        OfferSearchPage typoResults = search.search("mseemn", null, null, null, null,
                "Casablanca", "Maarif", true, "pickup_end", 0, 10, now);

        assertThat(suggestions).extracting(OfferSearchDocument::id).contains(offerId);
        assertThat(arabicSuggestions).extracting(OfferSearchDocument::id).contains(offerId);
        assertThat(results.ids()).contains(offerId);
        assertThat(results.total()).isEqualTo(1);
        assertThat(typoResults.ids()).contains(offerId);
    }
}
