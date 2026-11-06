# KULHA! M4 backend self-check — follow-up overlay

**Execution status:** Every test added or changed by this follow-up is **NOT RUN** in the preparation environment. The founder reported that the preceding version passed 160 tests with 0 failures and 0 errors on 2026-11-05; that run predates these changes and is not evidence for this follow-up. No new PASS is claimed.

## B.0 exit criteria

| Acceptance criterion | Test / evidence | File | Status | Known risk / note |
|---|---|---|---|---|
| KUL-030..033 automated acceptance criteria | Full `mvn test` | Complete API suite | NOT RUN | Founder must run after V8 has applied. |
| No more than 50 active offers per merchant under concurrent create | `concurrentCreationNeverExceedsFiftyActiveOffersForOneMerchant` | `src/test/java/ma/kulha/offer/OfferBackendHardeningIntegrationTest.java` | NOT RUN | Merchant row lock serializes cap check + insert. |
| Republish an `UNPUBLISHED` offer cannot push the merchant above 50 active offers | `republishingAnUnpublishedOfferRespectsTheFiftyActiveOfferLimit` | `src/test/java/ma/kulha/offer/OfferBackendHardeningIntegrationTest.java` | NOT RUN | `DRAFT` is already counted; republish adds a slot and checks under the merchant row lock. |
| Only approved merchants can create/publish | `pendingMerchantCannotCreateAnOfferAndApprovedMerchantReservationLocksPrice`, `rejectedMerchantCannotCreateOrPublishOffers`, `merchantSuspensionSuspendsPublishedOffersAndApprovalDoesNotRepublishThem` | `src/test/java/ma/kulha/offer/OfferLifecycleIntegrationTest.java`, `OfferAuthorizationAndTransitionIntegrationTest.java` | NOT RUN | Includes pending/rejected/suspended states. |
| Pending merchant cannot publish a pre-existing draft | `pendingMerchantCannotPublishAnExistingDraft` | `src/test/java/ma/kulha/offer/OfferAuthorizationAndTransitionIntegrationTest.java` | NOT RUN | Draft is seeded before the denied publish request. |
| Merchant A cannot read/change Merchant B's offer | `anotherMerchantCannotReadEditOrPublishAnOffer` | `src/test/java/ma/kulha/offer/OfferAuthorizationAndTransitionIntegrationTest.java` | NOT RUN | Service and HTTP checks; verifies target state is unchanged. |
| Concurrent stock cannot exceed total or become negative | `fiftyConcurrentStockHoldsCannotReserveMoreThanTenUnits`, `stockHoldHandlesTheExactLastUnitAndDatabaseRejectsReservedAboveTotal`, `mixedHoldAndReleaseStormKeepsStockWithinBounds` | `src/test/java/ma/kulha/offer/OfferBackendHardeningIntegrationTest.java` | NOT RUN | Uses real PostgreSQL conditional UPDATEs and concurrent threads. |
| Publish/unpublish/quantity/suspension audit is transactional | `failedAuditInsertRollsBackTheOfferTransition`, `merchantCanCreateEditPublishUnpublishAndCancelDraftWhileAdminCanSuspendAndReinstate` | `src/test/java/ma/kulha/offer/OfferBackendHardeningIntegrationTest.java`, `OfferLifecycleIntegrationTest.java` | NOT RUN | Trigger failure test proves offer + audit rollback for publish; lifecycle test checks exact action counts. |
| ADR, OpenAPI, event schema, DDD and performance evidence | `ModuleBoundariesTest`, `twentyOfferListAndCountUseTwoOfferQueriesNotNPlusOneQueries`, EXPLAIN and k6 | `docs/adr/ADR-004-offer-module.md`, `docs/api/offers.openapi.yaml`, `docs/events/offer-events.v1.md`, `docs/perf/` | NOT RUN | Docs and tests are present; real plans and load measurements remain unavailable. |
| Loading skeletons and loader curtain | Web tests and browser visual check | Web repository | NOT RUN | Not part of this backend follow-up. |
| Agent report gives commands and actual results | `HOW_TO_TEST.md` and this report | API ZIP root / `docs/reviews/` | NOT RUN | Founder-reported baseline is stated separately; follow-up results are not fabricated. |

## Domain and API coverage

| Requirement | Test name | File | Status |
|---|---|---|---|
| State transitions and forbidden transitions | `unpublishAndRepublishFollowTheAllowedStateMachine`, `cancelIsAllowedOnlyForDraftOffers`, `suspensionAndReinstatementReturnToUnpublished` | `src/test/java/ma/kulha/offer/domain/model/OfferTest.java` | NOT RUN |
| Price, quantity, allergens and pickup rules | `quantityCannotDropBelowReservedUnits`, `allergensAndPriceInvariantsAreEnforced`, `publishReportsEveryMissingPickupField` | same | NOT RUN |
| Cutoff boundary and configurable cutoff | `reservabilityUsesTheConfiguredCutoffAndRejectsTheExactBoundary` | same | NOT RUN |
| Pickup window max 24 hours and end within 7 days | `publishRejectsPickupWindowsLongerThanTwentyFourHours`, `publishRejectsPickupEndMoreThanSevenDaysAhead` | same | NOT RUN |
| Duplicate clears pickup window | `duplicateClearsPickupWindowAndCreatesDraft`, `duplicateReplaysOneDraftAndKeepsItsPickupWindowCleared` | same; `OfferIdempotencyIntegrationTest.java` | NOT RUN |
| DB rejects one-sided pickup timestamps | `databaseRejectsAnOfferWithOnlyOnePickupTimestamp` | `src/test/java/ma/kulha/offer/OfferBackendHardeningIntegrationTest.java` | NOT RUN |
| DB requires reservation marker when reserved units are positive | `databaseRequiresFirstReservationTimestampWhenUnitsAreReserved` | same | NOT RUN |
| Create/edit/publish/unpublish/duplicate/cancel and admin moderation | `merchantCanCreateEditPublishUnpublishAndCancelDraftWhileAdminCanSuspendAndReinstate` | `src/test/java/ma/kulha/offer/OfferLifecycleIntegrationTest.java` | NOT RUN |
| Quantity/food-info audit entries | `merchantCanCreateEditPublishUnpublishAndCancelDraftWhileAdminCanSuspendAndReinstate` | same | NOT RUN |
| Create idempotency exact response replay and changed-body 409 | `createReplaysTheOriginalResponseAndRejectsAChangedRequestForTheSameKey` | `src/test/java/ma/kulha/offer/OfferIdempotencyIntegrationTest.java` | NOT RUN |
| Duplicate idempotency replay | `duplicateReplaysOneDraftAndKeepsItsPickupWindowCleared` | same | NOT RUN |
| Concurrent same-key requests create one offer | `concurrentRetriesWithTheSameKeyCreateExactlyOneOffer` | same | NOT RUN |
| Active-offer cap race | `concurrentCreationNeverExceedsFiftyActiveOffersForOneMerchant` | `src/test/java/ma/kulha/offer/OfferBackendHardeningIntegrationTest.java` | NOT RUN |
| 50 stock holds on 10 units; release floor | `fiftyConcurrentStockHoldsCannotReserveMoreThanTenUnits` | same | NOT RUN |
| Exact-last-unit and database quantity constraint | `stockHoldHandlesTheExactLastUnitAndDatabaseRejectsReservedAboveTotal` | same | NOT RUN |
| Price/pickup stay locked after released reservation | `priceAndPickupRemainLockedAfterAllReservedUnitsAreReleased` | `src/test/java/ma/kulha/offer/OfferBackendHardeningIntegrationTest.java` | NOT RUN |
| Mixed hold/release storm | `mixedHoldAndReleaseStormKeepsStockWithinBounds` | same | NOT RUN |
| Expiry in 500-row batches, audit and idempotent second run | `expiryProcessesTwelveHundredRowsInBatchesAndIsIdempotent` | same | NOT RUN |
| Audit rollback | `failedAuditInsertRollsBackTheOfferTransition` | same | NOT RUN |
| Cross-tenant isolation | `anotherMerchantCannotReadEditOrPublishAnOffer` | `src/test/java/ma/kulha/offer/OfferAuthorizationAndTransitionIntegrationTest.java` | NOT RUN |
| Stale edit race and HTTP 409 | `staleVersionAllowsOnlyOneConcurrentEdit` | same | NOT RUN |
| Merchant suspension and no auto-republish | `merchantSuspensionSuspendsPublishedOffersAndApprovalDoesNotRepublishThem` | same | NOT RUN |
| Stock-hold/suspension race | `merchantSuspensionSerializesWithAConcurrentStockHold` | same | NOT RUN |
| `isReservable` reflects merchant approval | `merchantSuspensionSuspendsPublishedOffersAndApprovalDoesNotRepublishThem`, `merchantSuspensionSerializesWithAConcurrentStockHold` | same | NOT RUN |
| Rejected merchant denied | `rejectedMerchantCannotCreateOrPublishOffers` | same | NOT RUN |
| Customer 403 and anonymous 401 | `customerAndAnonymousCallersCannotUseMerchantOfferRoutes` | same | NOT RUN |
| Field-level pickup errors and correlation audit | `missingPickupWindowReturns422WithEveryFieldErrorAndCorrelationIdIsAudited` | same | NOT RUN |
| 20-row list + count use two offer statements | `twentyOfferListAndCountUseTwoOfferQueriesNotNPlusOneQueries` | `src/test/java/ma/kulha/offer/infrastructure/persistence/OfferListStatementCountIntegrationTest.java` | NOT RUN |
| JSON request body limit returns 413 and accepts exact-limit bodies | `rejectsJsonBodyLargerThanConfiguredLimit`, `rejectsOversizedJsonBodyWhenContentLengthIsUnknown`, `passesJsonBodyAtConfiguredLimitUnchanged` | `src/test/java/ma/kulha/shared/web/RequestBodySizeLimitFilterTest.java` | NOT RUN |
| Explicit Hikari pool limits | Application context configuration; inspect runtime Hikari config | `src/main/resources/application.yml` | NOT RUN | Defaults are configured; founder should confirm effective values in local profile. |

## Implementation notes and deviations

1. **V8 additive migration:** The first M4 delivery included V7 and the founder may already have applied it. This follow-up therefore adds `V8__offer_idempotency.sql` for the idempotency table, stricter two-sided pickup-window CHECK, and permanent first-reservation marker. V1–V7 are not modified; V7 checksum was compared with the earlier M4 overlay and is unchanged.
2. **Idempotency:** Create and duplicate use optional actor-scoped `Idempotency-Key`. A PostgreSQL transaction-scoped advisory lock serializes concurrent retries. The stored JSONB snapshot preserves the original response even if the offer is edited later. A changed request on the same key returns 409. No retention/cleanup job is included; define a replay-retention policy before production use.
3. **Offer cap:** Create/duplicate/publish lock the merchant row and re-check approval inside the transaction. This also serializes those commands with merchant approve/suspend status updates.
4. **Stock/edit race:** Offer updates compare both optimistic `version` and the previously read `quantity_reserved`; a concurrent stock hold cannot be silently overwritten by an edit.
5. **Suspension race:** The merchant-suspension listener re-reads and locks each published offer inside a transaction before suspending. Its audit entry shares the transaction; `OfferSuspended` is published after commit.
6. **Cutoff configuration:** `APP_OFFER_RESERVATION_CUTOFF_MINUTES` defaults to 30 and is used by both `OfferApi.isReservable` and the conditional stock UPDATE. `APP_OFFER_EXPIRY_DELAY_MS` controls the scheduled expiry delay.
7. **Permanent first-reservation lock:** V8 adds and backfills `reservation_started_at`; first successful `tryHoldUnits` sets it, `releaseUnits` never clears it, and domain + SQL edit guards preserve the D6 lock after available units return to their original count. The DB CHECK requires the marker whenever `quantity_reserved > 0`.
8. **Performance evidence:** The SQL-count test is implemented for the repository's list + count (two offer statements). It does not instrument the entire HTTP request including the bounded merchant-owner lookup. EXPLAIN on about 50,000 seeded offers and the one-minute k6 run are NOT RUN; no p95 claim is made.
9. **TOCTOU:** `OfferApi.isReservable` and `tryHoldUnits` remain separate operations by design. M6 must re-check merchant approval after a successful hold and release if approval was revoked; M7 must re-check merchant and offer before payment confirmation.
10. **Frontend:** Skeleton and loader work belongs to the separate web archive. This backend follow-up does not claim pnpm, browser, or visual test results.

## Commands and actual results

- Founder-reported baseline before this follow-up: `mvn test` — **160 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS**, finished 2026-11-05 at 03:27:31 +01:00.
- Follow-up focused tests: **NOT RUN** in the preparation environment; Maven could not be obtained and PostgreSQL was not accessible.
- Follow-up full `mvn test`: **NOT RUN**.
- EXPLAIN, k6, and browser visual checks: **NOT RUN**.

Run the command sequence in `HOW_TO_TEST.md` and replace these NOT RUN statuses only with captured real output.

## Founder-reported regression correction (2026-11-05)

A subsequent run of the backend follow-up reported 195 tests with two failures and one error. A follow-up patch corrects the test's executor/latch setup, moves stock-release failure translation outside the JDBC `@Repository` adapter, and propagates request correlation IDs to audit rows. It also replaces the reported deprecated Spring HTTP status/response methods. The added/changed tests and the full suite remain **NOT RUN** by the packaging agent; the founder must execute the targeted commands in `HOW_TO_TEST.md` and paste the real output before this gate can be marked PASS.
