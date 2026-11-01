
## 2026-11-01 — Backend security review

- Added persistent RS256 signing-key configuration with startup validation and explicit local-only ephemeral-key opt-in.
- Added regression coverage for JWT key-pair validation and verification-link resend invalidation.
- Resending email verification now invalidates older unused verification links.
- Registration now suppresses only PostgreSQL duplicate-key errors; unrelated database integrity errors surface as failures.
- Added a safe `.env.example`, key-generation instructions and an OAuth status/security review.
- Cleaned source handoff omits `.env`, `.git`, `.idea` and `target` build output.
- Google OAuth/OIDC login is not implemented in this API. The existing architecture decision uses first-party authentication; see `docs/security/API_SECURITY_REVIEW_2026-11-01.md`.
