# Elasticsearch offer search

## Decision

PostgreSQL remains the transactional source of truth. Elasticsearch owns public offer discovery: text search, autocomplete, prefix matching, typo tolerance, ranking, and the search-side filters. Public result pages hydrate the IDs returned by Elasticsearch from PostgreSQL before returning prices, stock, pickup data, allergen data, and merchant details. This keeps Elasticsearch out of the reservation and checkout consistency boundary.

The adapter uses Spring's `RestClient` over Elasticsearch's HTTP API. The project manages dependencies through Maven (`pom.xml`) and intentionally uses the HTTP API instead of adding a second Java client dependency; the trade-off is that the Elasticsearch DSL is represented as JSON maps rather than compile-time typed client builders. The index schema is created by the adapter on first use and versioned as `kulha-offers-v3`.

## Data flow

1. Offer mutations commit to PostgreSQL.
2. The offer application service publishes an `OfferEventEnvelope` after commit for ordinary offer changes. Bulk expiry publishes one `OfferExpiryBatchEvent` per committed database batch; reservation hold/release operations publish the same single-offer refresh signal after their transaction commits.
3. `OfferSearchIndexListener` queues changed offer IDs. A bounded worker fetches up to 50 offers and approved public merchant profiles in batches, then sends one Elasticsearch bulk request. Synchronous test-mode expiry drains these queued batches instead of issuing one Elasticsearch refresh per expired offer. A published offer is indexed while its pickup window remains outside the reservation cutoff and it belongs to an approved merchant. Sold-out documents remain searchable only when the caller disables the available-only filter; suggestions always require positive stock. All non-public states delete the document.
4. Search and suggestions query Elasticsearch. Search results are hydrated from PostgreSQL in the exact Elasticsearch ranking order; stale or no-longer-public IDs are omitted.
5. At application startup, `OfferSearchRebuildListener` scans offer IDs in batches of 250 and reconciles each document. This is a recovery path for updates missed while Elasticsearch was unavailable.

Elasticsearch failures are logged and do not roll back a committed PostgreSQL transaction. The application does not silently fall back to SQL `LIKE` autocomplete or SQL full-text search, because doing so would reintroduce the database load this design is intended to remove. If Elasticsearch is unavailable, search endpoints fail rather than shifting the load back to PostgreSQL.

## Resource profile

The previous discovery path performed text predicates and a separate count query in PostgreSQL. The new path moves text matching, autocomplete, ranking, filters and hit counting to Elasticsearch. Autocomplete reads only Elasticsearch; a full result page performs one bounded PostgreSQL offer-ID hydration query plus the existing batched approved-merchant profile lookup. Index updates are also batched rather than issuing one offer read per document. This materially reduces the expensive text-search work on PostgreSQL, but it does not make Elasticsearch free: it consumes its own heap, CPU and disk. The local container is capped at a 512 MiB JVM heap, and production should size Elasticsearch separately based on measured index size and traffic rather than assuming total infrastructure RAM will fall.

## Search behavior

- Prefix suggestions use `multi_match` with `bool_prefix`, edge n-grams (2–15), and a maximum of eight suggestions at the API boundary.
- General search uses multi-field relevance with title and merchant boosts, `AUTO` fuzziness, structured filters, and deterministic secondary sorting.
- French/English fields use lowercase and ASCII folding. Arabic-analyzed mirrors (`titleArabic`, `descriptionArabic`, `merchantNameArabic`, `cityArabic`, `districtArabic`) are sibling root fields, each with at most one edge-ngram autocomplete subfield. This avoids Elasticsearch-invalid chained multi-fields while retaining Arabic prefix matching.
- Category, city, district, price, pickup-window, available-stock and reservation-cutoff constraints are applied in Elasticsearch.
- Approved merchant documents include a `shopLocation` `geo_point` when a merchant has supplied a valid map pin. Nearby searches apply a `geo_distance` radius filter and `_geo_distance` ascending sort; the public API returns a rounded `distanceKm` on each result. Offers without shop coordinates remain discoverable in normal search but are excluded from nearby results.
- PostgreSQL re-hydration is authoritative for public response fields and prevents a stale index from exposing an offer that is no longer public.

## Local development

From the API project root, run `scripts/dev-up.ps1` to start the local PostgreSQL/Mailpit stack and Elasticsearch. To manage Elasticsearch separately, run from `infra/elasticsearch`:

```bash
docker compose up -d
```

The local-only service binds to `127.0.0.1:9200`, disables Elasticsearch security only for development, and limits the JVM heap to 512 MiB. Configure the API with:

```dotenv
ELASTICSEARCH_URL=http://localhost:9200
ELASTICSEARCH_OFFERS_INDEX=kulha-offers-v3
ELASTICSEARCH_REPLICAS=0
```

The index is created automatically. Existing published offers are indexed in batches after application startup.

## Production requirements

- Run Elasticsearch as a separate managed cluster or dedicated service; do not colocate its heap with the PostgreSQL process on a memory-constrained host.
- Set `ELASTICSEARCH_URL` to an HTTPS endpoint. If credentials are configured, the adapter rejects a non-HTTPS URL.
- Set `ELASTICSEARCH_USERNAME` and `ELASTICSEARCH_PASSWORD` through the deployment secret manager, never in source control. Configure the JVM truststore for the cluster CA.
- Use `ELASTICSEARCH_REPLICAS=1` or higher only when the cluster has enough data nodes. The development default is zero replicas.
- Keep PostgreSQL connection-pool sizing independent from Elasticsearch capacity. Monitor Elasticsearch heap, old-generation pressure, CPU, disk, indexing lag, rejected thread-pool tasks, and search latency alongside PostgreSQL CPU and connection utilization.
- Snapshot the index and test restore/rebuild procedures. A rebuild reads offers from PostgreSQL in 250-row batches.
- Apply an explicit retention and index-version migration plan before changing mappings. Create a new versioned index and reindex before switching traffic for incompatible mapping changes.

## Consistency boundary and remaining hardening

Search is eventually consistent by design. PostgreSQL remains authoritative for reservations and all returned offer facts. The current recovery mechanism is event-driven updates, a bounded in-memory retry queue, and startup reconciliation. The queue is not a durable delivery guarantee while a process is running; startup reconciliation repairs missed changes after restart. Merchant profile changes and approval transitions emit `MerchantProfileUpdated` after commit; the search listener reindexes that merchant's published offers when the shop name, address, district or map pin changes. For stronger delivery guarantees and operational visibility, the next reliability improvement should be a transactional PostgreSQL outbox with retry state and dead-letter visibility.

## Tests

- `OfferDiscoveryServiceTest` verifies that both full discovery and autocomplete delegate to Elasticsearch, result pages hydrate current PostgreSQL rows, and nearby result distances are rounded consistently.
- `OfferSearchIndexListenerTest` verifies public-offer indexing and removal of non-public offers.
- `OfferDiscoveryIntegrationTest` clears a dedicated `kulha-offers-test-v2` index between cases, uses localhost Elasticsearch only, disables startup rebuild, and uses synchronous event indexing. It never clears the normal `kulha-offers-v3` index. The scheduled offer-expiry worker is disabled only in the test profile to keep manual batch-expiry assertions deterministic; it remains enabled by default elsewhere.
- `ElasticsearchOfferSearchIntegrationTest` runs against a real Elasticsearch node when `ELASTICSEARCH_INTEGRATION_URL` is set. It exercises indexing, French prefix suggestions, Arabic prefix suggestions, structured location filters, full search, geo mapping, radius filtering and nearest-first sorting.
- Start a local node and set `ELASTICSEARCH_INTEGRATION_URL=http://localhost:9200` to enable the real integration test. The test uses a unique index and deletes it afterward.
