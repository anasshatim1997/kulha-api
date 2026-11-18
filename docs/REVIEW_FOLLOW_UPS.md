# Code Review #1 follow-ups

These items are intentionally not part of the R1–R4 fixes. Track them before the public-launch gate or resolve the product decisions with the owner.

## API engineering and security

- [ ] **Registration timing / outbox:** move verification-email delivery off the synchronous registration request, using a bounded executor after commit as an interim measure; replace it with the Sprint 8 transactional outbox.
- [ ] **Email delivery failures:** `SmtpEmailSender` currently logs and returns on delivery failure. Surface delivery state through the outbox before public exposure.
- [x] **Account-level login limiter:** account counters are incremented only after invalid credentials; successful logins do not consume the failure budget. See `AuthenticationService.login` and `RateLimitIntegrationTest`.
- [ ] **Refresh single-flight scope:** document that the 10-second refresh reuse cache is per Next.js instance and does not coordinate across BFF instances; retain the existing token-family security behavior.
- [x] **Audit correlation:** `AuditService` passes the request correlation ID (or a generated fallback) into `JdbcAuditLogWriter`; the insert persists `correlation_id`.
- [x] **Audit DB role:** provisioning SQL and ownership requirements are documented in `docs/security/AUDIT_LOG_PRODUCTION_ROLE.md`; applying it requires the production DBA role and is not a Flyway migration.
- [ ] **Credential hygiene:** owner to confirm whether any local `.env` values in supplied archives were real or reused and rotate SMTP, database, or bootstrap credentials if so.
- [x] **Release CI controls:** source workflow added under `.github/workflows/security-scanning.yml`; execution requires the repository CI environment and complete root build/container files.
- [x] **JPA source/configuration check:** no JPA entities, repositories, EntityManager or persistence annotations are used by application code; JDBC + Flyway are the persistence path. Removed unused JPA `ddl-auto` and `open-in-view` configuration. The dependency declaration itself could not be removed because the supplied export omits `pom.xml`.

## Product / architecture decisions requiring owner confirmation

- [ ] Confirm whether APPROVED merchants may edit identity/address details without re-review; consider audit visibility or re-approval for name/address changes.
- [ ] Decide whether an administrator may approve their own merchant application.
- [ ] Confirm whether unverified accounts may log in, consistent with the product's verification policy.
- [ ] Revisit ADR-002's first-party JWT approach before public launch; the accepted ADR already records the deviation from the original OIDC direction.

## Account profile editing

- `PUT /api/v1/me` allows the authenticated account holder to update `fullName` and optional `phone` only.
- The account ID is sourced exclusively from the validated JWT subject; clients cannot select another account.
- Email, roles, verification state and account status are immutable through this endpoint.
- Request and response contract: `docs/api/identity.openapi.yaml`.
