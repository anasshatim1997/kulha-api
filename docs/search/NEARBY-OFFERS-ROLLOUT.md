# Nearby offers rollout

This release adds coordinate-based offer discovery without persisting customer location.

## How the request flows

1. A customer explicitly presses **Near me** in the Offers page and grants browser location permission.
2. The browser sends latitude/longitude and the selected radius in the body of `POST /api/bff/offers/nearby`. It does not put coordinates in a URL, cookie, local storage, or customer profile.
3. The Next.js BFF validates the coordinates and forwards the request to `POST /api/v1/offers/nearby` with no-store response headers.
4. The API searches the `shopLocation` Elasticsearch `geo_point` field within the requested radius (100–25,000 m), sorts nearest first, and returns a rounded `distanceKm` for each result.
5. The frontend presents the radius, result count, rounded distance, and an easy way to return to all baskets.

The customer location is used for that request only. Merchant coordinates are shop profile data and are stored in PostgreSQL.

## Deployment checklist

- Apply Flyway migration `V12__merchant_geolocation.sql`. Normal application startup applies it automatically.
- Run Elasticsearch 8.13.4 or a compatible 8.x deployment. For local development:

  ```bash
  docker compose -f infra/elasticsearch/docker-compose.yml up -d
  ```

- Configure `ELASTICSEARCH_URL` (and credentials when required) and set `ELASTICSEARCH_OFFERS_INDEX=kulha-offers-v3`.
- Keep `ELASTICSEARCH_REBUILD_ON_STARTUP=true` for the initial rollout. The application creates the v3 mapping and queues existing offers for reindexing at startup. Do not point the new mapping at the old v2 index.
- Deploy the backend before, or at the same time as, the frontend. The frontend's BFF route requires the new public nearby endpoint.
- The GitHub Actions backend workflow now starts both PostgreSQL and Elasticsearch so the integration suite can exercise the real geo query.

## Merchant onboarding and existing shops

- New merchant applications can include an optional map pin. A merchant without a pin can still publish offers, and those offers remain available through normal browsing.
- Existing approved merchants can open the merchant dashboard, select **Edit shop details & location**, add/update the shop pin, and save. The existing pickup instructions are carried through the profile form.
- A merchant profile update or approval queues the merchant's published offers for reindexing after the database transaction commits.
- Offers without a valid shop pin are intentionally excluded from nearby results until the merchant adds one. They are not removed from standard offer search.

## Limits and privacy

- The current API accepts radii from 100 m to 25 km. The Offers UI offers 1, 3, 5, 10, and 25 km presets.
- Distances are rounded to 0.1 km before returning to the browser. Merchant coordinates are never included in public offer responses.
- Customer coordinates are not stored by KULHA. The browser requests fresh location only after the customer taps the control. The BFF response is marked `no-store`.
- Production deployments should serve the app over HTTPS because browser geolocation requires a secure context (localhost is also supported by browsers for development).


## Elasticsearch timeout and index-version hardening

- This geo-enabled release must use `ELASTICSEARCH_OFFERS_INDEX=kulha-offers-v3`. The adapter now fails fast when it is explicitly configured to use `kulha-offers-v2`; the older index is not deleted or modified.
- `ELASTICSEARCH_CONNECT_TIMEOUT_MS` defaults to 2000 ms and `ELASTICSEARCH_READ_TIMEOUT_MS` defaults to 10000 ms. These are read by the adapter (the previous 3000 ms example setting was not wired to the client).
- Bulk indexing no longer uses `refresh=wait_for` on every 50-document batch in normal runtime. Elasticsearch's normal refresh interval makes writes searchable shortly afterwards without forcing each batch to wait for a refresh. `app.search.elasticsearch.refresh-after-write=true` is enabled only in the test profile to preserve synchronous test assertions; standalone Elasticsearch integration tests explicitly refresh their isolated index before read-after-write assertions.
- If logs show requests to `kulha-offers-v2`, check both the API project's `.env` and the process/IDE environment variables. Set `ELASTICSEARCH_OFFERS_INDEX=kulha-offers-v3`, then restart the API.
- On Windows, the package includes `scripts/fix-elasticsearch-config.ps1`. Run it from PowerShell to update the API `.env` safely without touching PostgreSQL, JWT, or mail settings: `powershell -ExecutionPolicy Bypass -File .\scripts\fix-elasticsearch-config.ps1`. Then restart the API. Process-level environment variables still take precedence over `.env`; remove or update any `ELASTICSEARCH_OFFERS_INDEX=kulha-offers-v2` defined in your IDE/run configuration.
