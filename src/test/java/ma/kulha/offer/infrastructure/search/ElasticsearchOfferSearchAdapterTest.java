package ma.kulha.offer.infrastructure.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
