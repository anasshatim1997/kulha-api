package ma.kulha.offer.infrastructure.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class ElasticsearchOfferSearchAdapterTest {

    @Test
    void rejectsLegacyIndexBecauseItDoesNotHaveTheNearbyGeoMapping() {
        assertThatThrownBy(() -> new ElasticsearchOfferSearchAdapter(
                RestClient.builder(), new ObjectMapper(), "http://localhost:9200",
                "kulha-offers-v2", "", "", 0, 2000, 10000, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kulha-offers-v3");
    }

    @Test
    void arabicFieldsAreRootPropertiesAndDoNotUseChainedMultiFields() throws Exception {
        ElasticsearchOfferSearchAdapter adapter = new ElasticsearchOfferSearchAdapter(
                RestClient.builder(), new ObjectMapper(), "http://localhost:9200",
                "kulha-offers-test-v2", "", "", 0, 2000, 10000, false);
        Method definitionMethod = ElasticsearchOfferSearchAdapter.class.getDeclaredMethod("indexDefinition");
        definitionMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> definition = (Map<String, Object>) definitionMethod.invoke(adapter);
        @SuppressWarnings("unchecked")
        Map<String, Object> mappings = (Map<String, Object>) definition.get("mappings");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) mappings.get("properties");

        assertThat(properties).containsKeys("titleArabic", "descriptionArabic", "merchantNameArabic",
                "cityArabic", "districtArabic");
        assertThat(properties.get("shopLocation")).isEqualTo(Map.of("type", "geo_point"));
        assertThat(properties.get("savingsPercent"))
                .isEqualTo(Map.of("type", "scaled_float", "scaling_factor", 100));
        assertThat(hasChainedMultiFields(definition)).isFalse();
    }

    @Test
    void arabicMirrorFieldsNeverReceiveLatinSourceText() throws Exception {
        ElasticsearchOfferSearchAdapter adapter = new ElasticsearchOfferSearchAdapter(
                RestClient.builder(), new ObjectMapper(), "http://localhost:9200",
                "kulha-offers-test-v2", "", "", 0, 2000, 10000, false);
        Method documentMapMethod = ElasticsearchOfferSearchAdapter.class
                .getDeclaredMethod("documentMap", OfferSearchDocument.class);
        documentMapMethod.setAccessible(true);

        OfferSearchDocument latin = sampleDocument("Boulangerie", "Casablanca", "Maarif");
        @SuppressWarnings("unchecked")
        Map<String, Object> latinMap = (Map<String, Object>) documentMapMethod.invoke(adapter, latin);
        assertThat(latinMap).containsEntry("titleArabic", null)
                .containsEntry("cityArabic", null).containsEntry("districtArabic", null);

        OfferSearchDocument arabic = sampleDocument("مخبزة", "الدار البيضاء", "المعاريف");
        @SuppressWarnings("unchecked")
        Map<String, Object> arabicMap = (Map<String, Object>) documentMapMethod.invoke(adapter, arabic);
        assertThat(arabicMap).containsEntry("titleArabic", "مخبزة")
                .containsEntry("cityArabic", "الدار البيضاء").containsEntry("districtArabic", "المعاريف");
    }

    private static OfferSearchDocument sampleDocument(String title, String city, String district) {
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        return new OfferSearchDocument(UUID.randomUUID(), UUID.randomUUID(), title, "Description", "BAKERY",
                1000, null, 10, 5, now, now.plusSeconds(3600), now, now,
                "Merchant", city, district, "Address", "Pickup instructions", List.of(), "DECLARED", null,
                33.58, -7.62);
    }

    @Test
    void bulkRequestsUseUtf8ForFrenchAndArabicOfferText() {
        assertThat(ElasticsearchOfferSearchAdapter.NDJSON.getCharset())
                .isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void includesTheElasticsearchItemFailureWhenBulkIndexingFails() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var response = mapper.readTree("""
                {"errors":true,"items":[{"index":{"_id":"offer-123","status":400,"error":{
                  "type":"mapper_parsing_exception",
                  "reason":"failed to parse field [pickupStart]",
                  "caused_by":{"reason":"invalid date value"}
                }}}]}
                """);

        assertThat(ElasticsearchOfferSearchAdapter.bulkFailureDetails(response))
                .contains("operation=index", "id=offer-123", "mapper_parsing_exception",
                        "failed to parse field [pickupStart]", "invalid date value");
    }

    private static boolean hasChainedMultiFields(Object value) {
        if (!(value instanceof Map<?, ?> map)) return false;
        Object fields = map.get("fields");
        if (fields instanceof Map<?, ?> children) {
            for (Object child : children.values()) {
                if (child instanceof Map<?, ?> childMap && childMap.containsKey("fields")) return true;
            }
        }
        return map.values().stream().anyMatch(ElasticsearchOfferSearchAdapterTest::hasChainedMultiFields);
    }
}
