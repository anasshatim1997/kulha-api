package ma.kulha.offer.infrastructure.search;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.StringJoiner;
import ma.kulha.offer.domain.model.OfferCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class ElasticsearchOfferSearchAdapter implements ElasticsearchOfferSearch {
    private static final Logger log = LoggerFactory.getLogger(ElasticsearchOfferSearchAdapter.class);
    static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson;charset=UTF-8");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final RestClient client;
    private final ObjectMapper mapper;
    private final String index;
    private final int replicas;
    private final boolean refreshAfterWrite;

    ElasticsearchOfferSearchAdapter(RestClient.Builder builder, ObjectMapper mapper,
                                    @Value("${app.search.elasticsearch.url:http://localhost:9200}") String url,
                                    @Value("${app.search.elasticsearch.index:kulha-offers-v3}") String index,
                                    @Value("${app.search.elasticsearch.username:}") String username,
                                    @Value("${app.search.elasticsearch.password:}") String password,
                                    @Value("${app.search.elasticsearch.replicas:0}") int replicas,
                                    @Value("${app.search.elasticsearch.connect-timeout-ms:2000}") long connectTimeoutMs,
                                    @Value("${app.search.elasticsearch.read-timeout-ms:10000}") long readTimeoutMs,
                                    @Value("${app.search.elasticsearch.refresh-after-write:false}") boolean refreshAfterWrite) {
        if (username.isBlank() != password.isBlank()) {
            throw new IllegalStateException("Elasticsearch username and password must be configured together.");
        }
        if (!username.isBlank() && !url.startsWith("https://")) {
            throw new IllegalStateException("Elasticsearch credentials require an HTTPS URL.");
        }
        if (replicas < 0) {
            throw new IllegalArgumentException("Elasticsearch replicas cannot be negative");
        }
        if (connectTimeoutMs < 250 || readTimeoutMs < 1000) {
            throw new IllegalArgumentException("Elasticsearch HTTP timeouts are below the safe minimum");
        }
        if ("kulha-offers-v2".equalsIgnoreCase(index.trim())) {
            throw new IllegalStateException("This release requires the geo-enabled Elasticsearch index 'kulha-offers-v3'. "
                    + "Set ELASTICSEARCH_OFFERS_INDEX=kulha-offers-v3; the existing v2 index is left untouched.");
        }
        var httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs)).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        var configured = builder.baseUrl(url).requestFactory(requestFactory);
        if (!username.isBlank()) {
            configured.defaultHeaders(headers -> headers.setBasicAuth(username, password));
        }
        this.client = configured.build();
        this.mapper = mapper;
        this.index = index;
        this.replicas = replicas;
        this.refreshAfterWrite = refreshAfterWrite;
    }

    @Override
    public void index(OfferSearchDocument document) {
        applyBatch(List.of(document), List.of());
    }

    @Override
    public void delete(UUID offerId) {
        applyBatch(List.of(), List.of(offerId));
    }

    @Override
    public void applyBatch(List<OfferSearchDocument> upserts, List<UUID> deletes) {
        if ((upserts == null || upserts.isEmpty()) && (deletes == null || deletes.isEmpty())) return;
        ensureIndex();
        // Production uses Elasticsearch's normal refresh interval. Waiting for refresh on every
        // 50-document batch made the old 3-second client timeout especially easy to hit.
        // Tests can opt into refresh=wait_for to keep synchronous read-after-write assertions deterministic.
        String bulkPath = refreshAfterWrite ? "/{index}/_bulk?refresh=wait_for" : "/{index}/_bulk";
        JsonNode response = client.post().uri(bulkPath, index)
                .contentType(NDJSON)
                .body(toBulkBody(upserts, deletes))
                .retrieve().body(JsonNode.class);
        if (response != null && response.path("errors").asBoolean()) {
            throw new IllegalStateException("Elasticsearch bulk request contained failed offer operations: "
                    + bulkFailureDetails(response));
        }
    }

    static String bulkFailureDetails(JsonNode response) {
        StringJoiner failures = new StringJoiner("; ");
        JsonNode items = response == null ? null : response.path("items");
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                for (String operationName : List.of("index", "create", "update", "delete")) {
                    JsonNode operation = item.path(operationName);
                    if (operation.isMissingNode() || operation.isNull()) continue;
                    JsonNode error = operation.path("error");
                    if (error.isMissingNode() || error.isNull()) continue;
                    String id = operation.path("_id").asString("unknown");
                    String type = error.path("type").asString("unknown");
                    String reason = error.path("reason").asString("no reason supplied");
                    String causedBy = error.path("caused_by").path("reason").asString(null);
                    String detail = "operation=" + operationName + ", id=" + id
                            + ", status=" + operation.path("status")
                            + ", type=" + type + ", reason=" + reason;
                    if (causedBy != null && !causedBy.isBlank()) detail += ", caused_by=" + causedBy;
                    failures.add(detail);
                }
            }
        }
        if (failures.length() == 0) {
            String raw = response == null ? "no response body" : response.toString();
            return raw.length() > 1500 ? raw.substring(0, 1500) + "…" : raw;
        }
        String detail = failures.toString();
        return detail.length() > 3000 ? detail.substring(0, 3000) + "…" : detail;
    }

    private String toBulkBody(List<OfferSearchDocument> upserts, List<UUID> deletes) {
        StringBuilder ndjson = new StringBuilder();
        try {
            if (upserts != null) {
                for (OfferSearchDocument document : upserts) {
                    ndjson.append(mapper.writeValueAsString(Map.of("index", Map.of("_id", document.id().toString())))).append('\n');
                    ndjson.append(mapper.writeValueAsString(documentMap(document))).append('\n');
                }
            }
            if (deletes != null) {
                for (UUID offerId : deletes) {
                    ndjson.append(mapper.writeValueAsString(Map.of("delete", Map.of("_id", offerId.toString())))).append('\n');
                }
            }
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize Elasticsearch bulk request", exception);
        }
        return ndjson.toString();
    }

    @Override
    public void clear() {
        ensureIndex();
        client.post().uri("/{index}/_delete_by_query?refresh=true&conflicts=proceed", index)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", Map.of("match_all", Map.of())))
                .retrieve().toBodilessEntity();
    }

    /**
     * Extract only Arabic-script text for Arabic analyzer fields. A mixed value such as
     * "Msemen maison مسمن منزلي" must not mirror its Latin tokens into the Arabic field.
     */
    static String arabicTextOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        StringBuilder arabic = new StringBuilder();
        boolean hasArabic = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            int type = Character.getType(codePoint);
            boolean arabicScript = script == Character.UnicodeScript.ARABIC;
            boolean mark = type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK;
            boolean separator = Character.isWhitespace(codePoint)
                    || type == Character.CONNECTOR_PUNCTUATION
                    || type == Character.DASH_PUNCTUATION
                    || type == Character.START_PUNCTUATION
                    || type == Character.END_PUNCTUATION
                    || type == Character.INITIAL_QUOTE_PUNCTUATION
                    || type == Character.FINAL_QUOTE_PUNCTUATION
                    || type == Character.OTHER_PUNCTUATION;
            if (arabicScript) {
                arabic.appendCodePoint(codePoint);
                hasArabic = true;
            } else if (mark || separator) {
                arabic.appendCodePoint(codePoint);
            } else {
                arabic.append(' ');
            }
        }
        if (!hasArabic) return null;
        String result = arabic.toString().replaceAll("\\s+", " ").trim();
        return result.isEmpty() ? null : result;
    }

    private Map<String, Object> documentMap(OfferSearchDocument document) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("offerId", document.id().toString());
        body.put("merchantId", document.merchantId().toString());
        body.put("title", document.title());
        body.put("titleArabic", arabicTextOrNull(document.title()));
        body.put("description", document.description());
        body.put("descriptionArabic", arabicTextOrNull(document.description()));
        body.put("category", document.category());
        body.put("priceMinor", document.priceMinor());
        body.put("originalPriceMinor", document.originalPriceMinor());
        body.put("quantityTotal", document.quantityTotal());
        body.put("availableUnits", document.availableUnits());
        body.put("pickupStart", document.pickupStart().toString());
        body.put("pickupEnd", document.pickupEnd().toString());
        body.put("publishedAt", document.publishedAt() == null ? null : document.publishedAt().toString());
        body.put("createdAt", document.createdAt().toString());
        body.put("merchantName", document.merchantName());
        body.put("merchantNameArabic", arabicTextOrNull(document.merchantName()));
        body.put("city", document.city());
        body.put("cityArabic", arabicTextOrNull(document.city()));
        body.put("district", document.district());
        body.put("districtArabic", arabicTextOrNull(document.district()));
        body.put("merchantAddress", document.merchantAddress());
        body.put("pickupInstructions", document.pickupInstructions());
        body.put("allergens", document.allergens());
        body.put("allergensStatus", document.allergensStatus());
        body.put("foodNotes", document.foodNotes());
        body.put("imageUrl", document.imageUrl());
        body.put("shopLocation", document.latitude() == null || document.longitude() == null
                ? null : Map.of("lat", document.latitude(), "lon", document.longitude()));
        Integer original = document.originalPriceMinor();
        double savingsPercent = original != null && original > document.priceMinor()
                ? ((double) (original - document.priceMinor()) * 100.0 / original) : 0.0;
        body.put("savingsPercent", savingsPercent);
        return body;
    }

    @Override
    public List<OfferSearchDocument> suggest(String query, String city, String district, int limit, Instant reservableAfter) {
        ensureIndex();
        List<Object> filters = filters(city, district, null, null, null, true, false, null, null, null, reservableAfter);
        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("must", List.of(Map.of("multi_match", Map.of("query", query, "type", "bool_prefix",
                "fields", List.of("title.autocomplete^5", "titleArabic.autocomplete^5", "merchantName.autocomplete^3",
                        "merchantNameArabic.autocomplete^3", "city.autocomplete", "cityArabic.autocomplete",
                        "district.autocomplete", "districtArabic.autocomplete")))));
        bool.put("filter", filters);
        Map<String, Object> body = Map.of("size", limit, "_source", true,
                "query", Map.of("bool", bool),
                "sort", List.of(Map.of("_score", "desc"), Map.of("pickupEnd", "asc")));
        JsonNode root = postSearch(body);
        List<OfferSearchDocument> result = new ArrayList<>();
        for (JsonNode hit : root.path("hits").path("hits")) {
            result.add(readDocument(hit.path("_source")));
        }
        return List.copyOf(result);
    }

    @Override
    public OfferSearchPage search(String query, OfferCategory category, Integer maxPriceMinor,
                                  Instant pickupFrom, Instant pickupTo, String city, String district, boolean availableOnly,
                                  boolean lowStockOnly, String sort, int page, int size, Double latitude, Double longitude,
                                  Integer radiusMeters, Instant reservableAfter) {
        ensureIndex();
        List<Object> filters = filters(city, district, category, maxPriceMinor, pickupFrom, availableOnly, lowStockOnly,
                latitude, longitude, radiusMeters, reservableAfter);
        if (pickupTo != null) {
            filters.add(Map.of("range", Map.of("pickupStart", Map.of("lte", pickupTo.toString()))));
        }
        if ("savings".equals(sort)) filters.add(Map.of("range", Map.of("savingsPercent", Map.of("gt", 0))));
        boolean hasQuery = query != null && !query.isBlank();
        Map<String, Object> bool = new LinkedHashMap<>();
        if (hasQuery) {
            bool.put("must", List.of(Map.of("multi_match", Map.of("query", query,
                    "fields", List.of("title^5", "titleArabic^5", "merchantName^3", "merchantNameArabic^3",
                            "description", "descriptionArabic", "city", "cityArabic", "district", "districtArabic"),
                    "fuzziness", "AUTO", "max_expansions", 25, "operator", "and"))));
        } else {
            bool.put("must", List.of(Map.of("match_all", Map.of())));
        }
        bool.put("filter", filters);

        Map<String, Object> body = Map.of("from", page * size, "size", size,
                "track_total_hits", true, "_source", List.of("offerId"),
                "query", Map.of("bool", bool),
                "sort", sortOptions(sort, hasQuery, latitude, longitude));
        long requestStarted = System.nanoTime();
        JsonNode root = postSearch(body);
        long roundTripMillis = Duration.ofNanos(System.nanoTime() - requestStarted).toMillis();
        long elasticsearchTookMillis = root.path("took").asLong(-1);
        if (roundTripMillis >= 1_000 || elasticsearchTookMillis >= 1_000) {
            // ES's own `took` lets us distinguish query execution time from HTTP/client
            // overhead (including index initialization on the first request).
            log.warn("Slow Elasticsearch offer query: esTook={}ms, roundTrip={}ms, page={}, size={}, geoSort={}, textQuery={}",
                    elasticsearchTookMillis, roundTripMillis, page, size, "distance".equals(sort), hasQuery);
        }
        List<UUID> ids = new ArrayList<>();
        Map<UUID, Double> distances = new LinkedHashMap<>();
        for (JsonNode hit : root.path("hits").path("hits")) {
            String id = hit.path("_source").path("offerId").asString();
            if (!id.isBlank()) {
                UUID offerId = UUID.fromString(id);
                ids.add(offerId);
                JsonNode sortValues = hit.path("sort");
                if ("distance".equals(sort) && sortValues.isArray() && !sortValues.isEmpty() && sortValues.get(0).isNumber()) {
                    distances.put(offerId, sortValues.get(0).asDouble());
                }
            }
        }
        return new OfferSearchPage(ids, root.path("hits").path("total").path("value").asLong(ids.size()), distances);
    }

    private List<Object> sortOptions(String sort, boolean hasQuery, Double latitude, Double longitude) {
        List<Object> options = new ArrayList<>();
        if ("distance".equals(sort)) {
            if (latitude == null || longitude == null) throw new IllegalArgumentException("distance sort requires coordinates");
            options.add(Map.of("_geo_distance", Map.of("shopLocation", Map.of("lat", latitude, "lon", longitude),
                    "order", "asc", "unit", "m", "distance_type", "arc", "ignore_unmapped", true)));
            if (hasQuery) options.add(Map.of("_score", "desc"));
        } else if ("savings".equals(sort)) {
            options.add(Map.of("savingsPercent", "desc"));
            if (hasQuery) options.add(Map.of("_score", "desc"));
        } else if ("low_stock".equals(sort)) {
            options.add(Map.of("availableUnits", "asc"));
            options.add(Map.of("pickupEnd", "asc"));
        } else {
            String sortField = switch (sort) {
                case "price" -> "priceMinor";
                case "newest" -> "createdAt";
                default -> "pickupEnd";
            };
            String sortDirection = "newest".equals(sort) ? "desc" : "asc";
            boolean pickupEndSort = "pickup_end".equals(sort);
            if (hasQuery && pickupEndSort) options.add(Map.of("_score", "desc"));
            options.add(Map.of(sortField, sortDirection));
            if (hasQuery && !pickupEndSort) options.add(Map.of("_score", "desc"));
        }
        options.add(Map.of("offerId", "asc"));
        return options;
    }

    private List<Object> filters(String city, String district, OfferCategory category, Integer maxPriceMinor,
                                 Instant pickupFrom, boolean availableOnly, boolean lowStockOnly,
                                 Double latitude, Double longitude, Integer radiusMeters, Instant reservableAfter) {
        List<Object> filters = new ArrayList<>();
        filters.add(Map.of("range", Map.of("pickupEnd", Map.of("gt", reservableAfter.toString()))));
        if (availableOnly) filters.add(Map.of("range", Map.of("availableUnits", Map.of("gt", 0))));
        if (lowStockOnly) filters.add(Map.of("range", Map.of("availableUnits", Map.of("gte", 1, "lte", 2))));
        if (category != null) filters.add(Map.of("term", Map.of("category", category.name())));
        if (maxPriceMinor != null) filters.add(Map.of("range", Map.of("priceMinor", Map.of("lte", maxPriceMinor))));
        if (city != null && !city.isBlank()) {
            filters.add(Map.of("term", Map.of("city.keyword", city.trim().toLowerCase(Locale.ROOT))));
        }
        if (district != null && !district.isBlank()) {
            filters.add(Map.of("term", Map.of("district.keyword", district.trim().toLowerCase(Locale.ROOT))));
        }
        if (pickupFrom != null) filters.add(Map.of("range", Map.of("pickupEnd", Map.of("gte", pickupFrom.toString()))));
        if (latitude != null && longitude != null && radiusMeters != null) {
            filters.add(Map.of("geo_distance", Map.of("distance", radiusMeters + "m",
                    "shopLocation", Map.of("lat", latitude, "lon", longitude))));
        }
        return filters;
    }

    private JsonNode postSearch(Map<String, Object> body) {
        return client.post().uri("/{index}/_search", index).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(JsonNode.class);
    }

    private OfferSearchDocument readDocument(JsonNode source) {
        return new OfferSearchDocument(UUID.fromString(source.path("offerId").asString()),
                UUID.fromString(source.path("merchantId").asString()), source.path("title").asString(),
                source.path("description").asString(), source.path("category").asString(),
                source.path("priceMinor").asInt(), nullableInt(source.path("originalPriceMinor")),
                source.path("quantityTotal").asInt(), source.path("availableUnits").asInt(),
                instant(source.path("pickupStart")), instant(source.path("pickupEnd")),
                instant(source.path("publishedAt")), instant(source.path("createdAt")),
                source.path("merchantName").asString(), source.path("city").asString(),
                source.path("district").asString(null), source.path("merchantAddress").asString(null),
                source.path("pickupInstructions").asString(null),
                mapper.convertValue(source.path("allergens"), STRING_LIST),
                source.path("allergensStatus").asString(), source.path("foodNotes").asString(null),
                source.path("imageUrl").asString(null),
                source.path("shopLocation").path("lat").isNumber() ? source.path("shopLocation").path("lat").asDouble() : null,
                source.path("shopLocation").path("lon").isNumber() ? source.path("shopLocation").path("lon").asDouble() : null);
    }

    private Integer nullableInt(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node.asInt();
    }

    private Instant instant(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() || node.asString().isBlank()
                ? null : Instant.parse(node.asString());
    }

    private volatile boolean indexReady;

    private synchronized void ensureIndex() {
        if (indexReady) return;
        long initializationStarted = System.nanoTime();
        try {
            client.put().uri("/{index}", index).contentType(MediaType.APPLICATION_JSON)
                    .body(indexDefinition()).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException exception) {
            boolean alreadyExists = exception.getStatusCode().value() == 400
                    && exception.getResponseBodyAsString().contains("resource_already_exists_exception");
            if (!alreadyExists) throw exception;
        }
        client.put().uri("/{index}/_mapping", index).contentType(MediaType.APPLICATION_JSON)
                .body(indexDefinition().get("mappings")).retrieve().toBodilessEntity();
        client.put().uri("/{index}/_settings", index).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("index", Map.of("number_of_replicas", replicas))).retrieve().toBodilessEntity();
        indexReady = true;
        long initializationMillis = Duration.ofNanos(System.nanoTime() - initializationStarted).toMillis();
        if (initializationMillis >= 1_000) {
            log.warn("Elasticsearch offer index initialization took {}ms (index={})", initializationMillis, index);
        }
    }

    private Map<String, Object> indexDefinition() {
        Map<String, Object> settings = Map.of(
                "number_of_shards", 1,
                "number_of_replicas", replicas,
                "max_ngram_diff", 13,
                "analysis", Map.of(
                        "filter", Map.of(
                                "autocomplete_edge", Map.of("type", "edge_ngram", "min_gram", 2, "max_gram", 15),
                                "arabic_stemmer", Map.of("type", "stemmer", "language", "arabic")),
                        "normalizer", Map.of("lowercase_keyword",
                                Map.of("type", "custom", "filter", List.of("lowercase", "asciifolding"))),
                        "analyzer", Map.of(
                                "search_text", Map.of("tokenizer", "standard", "filter", List.of("lowercase", "asciifolding")),
                                "autocomplete", Map.of("tokenizer", "standard", "filter", List.of("lowercase", "asciifolding", "autocomplete_edge")),
                                "autocomplete_search", Map.of("tokenizer", "standard", "filter", List.of("lowercase", "asciifolding")),
                                "arabic_autocomplete", Map.of("tokenizer", "standard", "filter", List.of("lowercase", "arabic_normalization", "autocomplete_edge")),
                                "arabic_search", Map.of("tokenizer", "standard", "filter", List.of("lowercase", "arabic_normalization", "arabic_stemmer")))));

        // Elasticsearch permits multi-fields only one level deep. Arabic analyzers therefore
        // live on sibling root fields (titleArabic, merchantNameArabic, etc.), not title.ar.
        Map<String, Object> text = Map.of("type", "text", "analyzer", "search_text", "fields", Map.of(
                "autocomplete", Map.of("type", "text", "analyzer", "autocomplete", "search_analyzer", "autocomplete_search"),
                "keyword", Map.of("type", "keyword", "normalizer", "lowercase_keyword")));
        Map<String, Object> arabicText = Map.of("type", "text", "analyzer", "arabic_search", "fields", Map.of(
                "autocomplete", Map.of("type", "text", "analyzer", "arabic_autocomplete", "search_analyzer", "arabic_search")));

        Map<String, Object> mappings = Map.of("properties", Map.ofEntries(
                Map.entry("offerId", Map.of("type", "keyword")),
                Map.entry("merchantId", Map.of("type", "keyword")),
                Map.entry("title", text),
                Map.entry("titleArabic", arabicText),
                Map.entry("description", text),
                Map.entry("descriptionArabic", arabicText),
                Map.entry("merchantName", text),
                Map.entry("merchantNameArabic", arabicText),
                Map.entry("city", text),
                Map.entry("cityArabic", arabicText),
                Map.entry("district", text),
                Map.entry("districtArabic", arabicText),
                Map.entry("category", Map.of("type", "keyword")),
                Map.entry("priceMinor", Map.of("type", "integer")),
                Map.entry("originalPriceMinor", Map.of("type", "integer")),
                Map.entry("quantityTotal", Map.of("type", "integer")),
                Map.entry("availableUnits", Map.of("type", "integer")),
                Map.entry("pickupStart", Map.of("type", "date")),
                Map.entry("pickupEnd", Map.of("type", "date")),
                Map.entry("publishedAt", Map.of("type", "date")),
                Map.entry("createdAt", Map.of("type", "date")),
                Map.entry("merchantAddress", Map.of("type", "text", "index", false)),
                Map.entry("pickupInstructions", Map.of("type", "text", "index", false)),
                Map.entry("allergens", Map.of("type", "keyword", "index", false)),
                Map.entry("allergensStatus", Map.of("type", "keyword", "index", false)),
                Map.entry("foodNotes", Map.of("type", "text", "index", false)),
                Map.entry("imageUrl", Map.of("type", "keyword", "index", false)),
                Map.entry("shopLocation", Map.of("type", "geo_point")),
                Map.entry("savingsPercent", Map.of("type", "scaled_float", "scaling_factor", 100))));

        return Map.of("settings", settings, "mappings", mappings);
    }
}