# Stage map

This delivery is grouped in reviewer-friendly stage sections in `CHANGES.md`; files remain at their repository-relative paths for overlay. The complete self-check is `docs/reviews/M4-self-check.md`.

- Stage 0: merchant pickup instructions, acting-merchant seam, correlation ID propagation, and web OAuth state secret separation.
- Stage 1: ADR-004, Offers OpenAPI, and event schema.
- Stage 2: V7, Offer aggregate, repository interface/JDBC adapter, domain unit tests.
- Stage 3: merchant offer commands/queries, REST DTOs/controllers, authorization and Problem Details.
- Stage 4: conditional stock primitives, reservability snapshot API, expiry job and expiry audit.
- Stage 5: admin suspension/reinstatement/history, audit correlation, post-commit offer events, merchant-suspension cascade.
- Stage 6: merchant offer pages, BFF routes, FR/EN/AR copy, skeleton components.
- Stage 6b: KulhaCurtain lifecycle and persistent submit progress.
- Stage 7: self-check, review notes, test instructions, EXPLAIN recipe and k6 script.
