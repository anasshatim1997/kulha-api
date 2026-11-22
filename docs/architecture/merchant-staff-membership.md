# Merchant staff membership invariant

## Pilot business rule

A staff identity may have **zero or one active merchant membership** during the pilot. More than one active merchant membership is not supported. A revoked membership does not count as active and may be reactivated for the same merchant.

The global `MERCHANT_STAFF` identity role is a projection of active membership state:

- Grant `MERCHANT_STAFF` in the same transaction that creates or reactivates an active staff membership.
- Revoke `MERCHANT_STAFF` in the same transaction that revokes the last active membership.
- Never leave the role present when the identity has no active merchant membership.
- Never permit a second active membership, even when concurrent requests target different merchants.

Merchant ownership and administrator roles are separate identity states and are not staff memberships.

## Persistence and concurrency

The partial unique index `ux_merchant_active_staff_user` is the database backstop for the one-active-membership invariant. Membership mutations for an identity are serialized with a transaction-scoped PostgreSQL advisory lock keyed by the user UUID. The lock is acquired before inserting/reactivating or revoking membership and before synchronizing the global role.

The database constraint remains authoritative if a concurrent or legacy writer does not participate in the advisory-lock protocol. The service translates a uniqueness conflict into HTTP 409; it must not retry inside the same failed transaction.

## Regression coverage

The PostgreSQL integration test `MerchantStaffRoleConcurrencyIntegrationTest` races removal from one merchant against assignment to another. It verifies after each round that exactly one active membership remains and that the global `MERCHANT_STAFF` role matches the active-membership state. The unique-index conflict path is retried only after both transactions complete. The staff-list query-count test separately verifies a constant four SELECTs for small and larger teams.

Any future change that permits multiple merchant memberships requires an explicit pilot-scope change and coordinated redesign of tenant selection, authorization context, and role projection. It must not be inferred from schema implementation details.
