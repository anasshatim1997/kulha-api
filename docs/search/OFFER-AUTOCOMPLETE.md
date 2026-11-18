# Public offer autocomplete and search

## Endpoint

`GET /api/v1/offers/suggestions?q=<term>&city=<optional>&district=<optional>&limit=6`

The endpoint accepts 2–80 characters and returns at most eight compact offer matches. Suggestions use Elasticsearch edge-ngram prefix matching, Arabic normalization/stemming through dedicated sibling fields, and French/English lowercase + ASCII folding. Full public discovery also uses Elasticsearch for text matching, relevance ranking, sorting and structured search filters.

Published offers from approved merchants are indexed while their pickup window remains outside the reservation cutoff. Suggestions and the default available-only search require positive stock; sold-out offers can still appear when the caller explicitly disables the available-only filter. Search results are re-hydrated from PostgreSQL so the response uses current offer and merchant data. The API boundary caps the suggestions at eight results. The endpoint retains the existing dedicated rate limit; the browser debounces and cancels superseded requests.

## Search engine and consistency

PostgreSQL remains the transactional source of truth. Elasticsearch owns public offer search and autocomplete. The backend queues offer IDs after committed offer changes and stock hold/release operations, then updates Elasticsearch in bounded bulk batches. It performs a batched reconciliation pass at startup. Elasticsearch outages do not roll back PostgreSQL writes and do not trigger an expensive SQL-search fallback. Search is eventually consistent; PostgreSQL remains authoritative for reservation and returned offer details.

See [ELASTICSEARCH-SEARCH-ARCHITECTURE.md](ELASTICSEARCH-SEARCH-ARCHITECTURE.md) for the data flow, analyzers, local setup, production security, monitoring and consistency limitations.

## Browser path

The browser calls `/api/bff/offers/suggestions` only. Next.js BFF forwards anonymously to `/api/v1/offers/suggestions`, ignores customer cookies, forwards a correlation ID and trusted client IP, and applies the same mutation-origin guard as the public offers BFF. The browser never receives Elasticsearch credentials and never connects directly to the search cluster. The endpoint is GET-only.
