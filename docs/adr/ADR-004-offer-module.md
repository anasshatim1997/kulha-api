# ADR-004 — Offer module

- Status: accepted for the M4 implementation baseline
- Date: 2026-11-05
- Scope: KUL-030 through KUL-033 only

## Context

M4 adds merchant-managed surplus-food offers. M5 discovery, M6 reservations, M7 payment, broker-backed outbox delivery, Redis and customer-facing pages are out of scope. The offer module owns one aggregate, `Offer`, and exposes read-only snapshots to later modules.

## Decisions D1–D10

| ID | Accepted choice |
|---|---|
| D1 | `BREAD_PASTRY`, `PREPARED_MEALS`, `GROCERIES`, `FRUIT_VEG`, `DRINKS`, `SURPRISE_BASKET`, `OTHER` |
| D2 | MAD only; integer centimes; 100–200000; optional merchant-supplied original price must be at least the offer price |
| D3 | Quantity total 1–500 |
| D4 | At publish, pickup start is future, end is after start, window is at most 24 hours, and end is within seven days |
| D5 | Reservation cutoff is pickup end minus `app.offer.reservation-cutoff-minutes` (default 30); the same value is used by `OfferApi.isReservable` and the conditional stock SQL |
| D6 | After the first reservation, price/original price and pickup window are locked; quantity cannot fall below reserved units |
| D7 | Allergens are `UNKNOWN` or `DECLARED`; only the fixed code list is accepted; no inference or AI-generated food safety data |
| D8 | Admin may suspend; reinstatement returns to `UNPUBLISHED` and requires merchant republication |
| D9 | Staff are postponed; `MerchantApi.resolveActingMerchant(userId)` is the owner-only seam. `MerchantApi.lockForOfferMutation(merchantId)` is the same-transaction row-lock seam used by offer create/duplicate/publish. |
| D10 | Maximum 50 active (`DRAFT` + `PUBLISHED`) offers per merchant; create and duplicate lock the merchant row before count + insert, and republishing an `UNPUBLISHED` offer checks the cap under the same lock |

## Domain and persistence

The state machine is `DRAFT → PUBLISHED → UNPUBLISHED → PUBLISHED`, with cancellation from `DRAFT`, suspension from `PUBLISHED` or `UNPUBLISHED`, reinstatement to `UNPUBLISHED`, and expiry from `PUBLISHED` or `UNPUBLISHED`. Sold-out is derived from `quantityReserved == quantityTotal`; `availableUnits` is derived and never persisted.

PostgreSQL is the source of truth. `offer.offers` is introduced by V7, with database CHECK constraints and indexes for the merchant/status list and published expiry window. V8 adds `reservation_started_at`, a permanent marker set by the first successful stock hold and never cleared by `releaseUnits`; price and pickup-window fields remain locked even after every held unit is released. V8 backfills the marker for existing rows with reserved units and adds a CHECK requiring the marker whenever `quantity_reserved > 0`. The offer aggregate and repository interface live in `domain`; JDBC lives in `infrastructure`; REST DTOs and controllers live in `interfaces`. The offer module calls merchant and admin only through their `application` APIs.

The V7 draft model permits both pickup timestamps to be null. This is a deliberate refinement of the plan's initial `NOT NULL` schema: the required `duplicate` operation creates a new draft with the pickup window cleared. Publishing validates that both timestamps are present and satisfy D4. PostgreSQL CHECK semantics allow an UNKNOWN expression, so V8 replaces the original expression with an explicit two-branch constraint: both timestamps NULL, or both non-NULL and end after start. A single NULL timestamp is rejected by the database.

## Concurrency

Stock hold is one conditional `UPDATE`: it increments `quantity_reserved` only when the offer is published, before the configured cutoff (default 30 minutes), and enough units remain. A successful one-row update means success; zero rows means refusal. Release is also a conditional `UPDATE` that cannot decrement below zero. There is no read-then-write stock path and no Redis/application lock.

Merchant edits use `version` optimistic locking and also compare the previously read `quantity_reserved` and `reservation_started_at` in the update predicate. Stock holds/releases deliberately do not increment the merchant edit version, so these predicates close the race where a reservation could occur after a merchant read but before a price/window edit. The timestamp is permanent: release returns available quantity but does not unlock price or pickup fields. A stale write becomes HTTP 409. Price and pickup-window fields are guarded by the domain after the first reservation. The offer-cap row lock also serializes against merchant status updates; create, duplicate and publish re-check approval after acquiring it. Publishing a `DRAFT` does not add to the active count because it was already counted; publishing an `UNPUBLISHED` offer does add one and is refused when the merchant already has 50 active offers.

## Approval and merchant suspension

Merchant ownership is resolved from the authenticated JWT subject through `MerchantApi.resolveActingMerchant`; merchant IDs supplied by a client are never trusted. Create and publish require `MerchantApi.isApproved`. `OfferApi.isReservable` checks both offer state/time/stock and current merchant approval. A committed `MerchantSuspended` event causes the offer module to lock and re-read each currently published offer, suspend it, append a system-actor audit row in that offer transaction, and publish an `OfferSuspended` envelope after commit. The row lock serializes the cascade with concurrent stock holds. Reapproval does not automatically republish offers.

There is an acknowledged time-of-check/time-of-use window between `isReservable` and `tryHoldUnits`; stock SQL intentionally has no merchant-table join. M6 must re-check merchant approval after a successful hold and release the units if approval was revoked. M7 must re-check merchant and offer before payment confirmation.

## Audit and events

Merchant publish/unpublish, quantity/food-information changes, admin suspension/reinstatement, expiry, and suspension cascaded from a merchant are audited through `AuditApi` inside the action transaction. The fixed system actor used for expiry and merchant-suspension cascade is `00000000-0000-0000-0000-000000000001`. The API audit writer reads `correlationId` from MDC; `CorrelationIdFilter` accepts a safe `X-Correlation-Id` or generates one. The audit table remains append-only.

In-process `OfferEventEnvelope` events are emitted after commit with `eventId`, `type`, `schemaVersion=1`, UTC `occurredAt`, `aggregateId`, `correlationId`, and payload `{offerId, merchantId, status, availableUnits}`. Payloads contain no offer title, description or personal data. RabbitMQ/outbox delivery remains M9.

## Operational and scope notes

- Expiry is evaluated at read time through `OfferApi.isReservable`; the scheduled job marks due `PUBLISHED` and `UNPUBLISHED` rows `EXPIRED` using one bounded `UPDATE … RETURNING` batch of at most 500. The batch transaction appends one audit entry per returned row and registers events after commit. `FOR UPDATE SKIP LOCKED` supports overlapping scheduler instances.
- List responses use a narrow summary projection and cap page size at 50 (default 20). `OfferListStatementCountIntegrationTest` asserts two offer SQL statements for 20 summaries (one list query + one count query); owner resolution is one bounded MerchantApi query, not an N+1.
- Hikari is explicitly configured with maximum pool size 10, minimum idle 2 and a 30,000 ms connection timeout by default. `RequestBodySizeLimitFilter` caps JSON request bodies at 1,048,576 bytes and returns HTTP 413 with Problem Details; both are configurable through `.env`.
- Create and duplicate accept optional `Idempotency-Key` headers. Keys are scoped to the JWT actor, the request fingerprint is SHA-256, a transaction-scoped PostgreSQL advisory lock serializes concurrent retries, and the original offer response snapshot is stored for exact replay. Reuse with a different request returns 409.
- Because the original M4 V7 migration may already be applied in the founder's database, the idempotency table and corrected pickup-window CHECK are delivered as additive `V8__offer_idempotency.sql`. This is a deliberate deviation from the initial two-migration plan and avoids editing an applied V7 checksum. V1–V7 remain unchanged in this follow-up.
- Price and pickup-window edits compare the previously read `quantity_reserved` in the SQL update predicate as well as the `version`. That prevents a stock hold committed after the domain read from allowing a previously unlocked price/window edit.
- The offer module exposes only `OfferSnapshot` to other modules. M5/M6 must use `OfferApi`; M6 must re-check merchant approval after a successful hold and release if approval was revoked, because the documented check/use window is bounded but not zero. M7 must re-check merchant and offer before payment confirmation.
- Admin history is the append-only offer audit history and is not a reservation/order history. No customer discovery, reservation tables, payments, RabbitMQ, Redis, outbox or AI-generated allergen/offer content is included.
- Automated tests added in the follow-up are **NOT RUN** in the preparation environment. The founder-reported 160-test green baseline was for the preceding hotfix, not this follow-up. Run the new focused tests and full suite after applying V8.
- EXPLAIN plans on ~50,000 rows and k6 p95 measurements remain **NOT RUN**. The SQL statement-count test is implemented but also awaits execution against PostgreSQL.
