# Code Review #1 follow-ups

These items are intentionally not part of the R1–R4 fixes. Track them before the public-launch gate or resolve the product decisions with the owner.

## API engineering and security

- [ ] **Registration timing / outbox:** move verification-email delivery off the synchronous registration request, using a bounded executor after commit as an interim measure; replace it with the Sprint 8 transactional outbox.
- [ ] **Email delivery failures:** `SmtpEmailSender` currently logs and returns on delivery failure. Surface delivery state through the outbox before public exposure.
- [ ] **Account-level login limiter:** assess whether counting all attempts lets an attacker lock out a known account; consider failed-attempt-only counting and progressive delay.
- [ ] **Refresh single-flight scope:** document that the 10-second refresh reuse cache is per Next.js instance and does not coordinate across BFF instances; retain the existing token-family security behavior.
- [ ] **Audit correlation:** write `correlation_id` through the audit append path.
- [ ] **Audit DB role:** create/document a least-privilege production role that cannot UPDATE, DELETE, or TRUNCATE `admin.audit_log`; the append-only trigger alone does not prevent TRUNCATE.
- [ ] **Credential hygiene:** owner to confirm whether any local `.env` values in supplied archives were real or reused and rotate SMTP, database, or bootstrap credentials if so.
- [ ] **Release CI controls:** add dependency, secret, and container/image scanning before release.

## Product / architecture decisions requiring owner confirmation

- [ ] Confirm whether APPROVED merchants may edit identity/address details without re-review; consider audit visibility or re-approval for name/address changes.
- [ ] Decide whether an administrator may approve their own merchant application.
- [ ] Confirm whether unverified accounts may log in, consistent with the product's verification policy.
- [ ] Revisit ADR-002's first-party JWT approach before public launch; the accepted ADR already records the deviation from the original OIDC direction.
