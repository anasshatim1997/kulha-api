# KULHA API security review — 2026-11-01

Scope: source contained in the backend ZIP provided for review. This is a code/configuration review, not a penetration test or legal/compliance certification.

## Changes made in this pass

1. **JWT signing key persistence is now configurable and production-safe by default.** The API accepts a stable RSA key pair through `APP_SECURITY_JWT_PRIVATE_KEY_BASE64` and `APP_SECURITY_JWT_PUBLIC_KEY_BASE64`. It checks that both halves are present, at least 2048 bits, and cryptographically match. The key id is configurable with `APP_SECURITY_JWT_KEY_ID`.
2. **Ephemeral JWT keys now require explicit opt-in.** `APP_SECURITY_JWT_ALLOW_EPHEMERAL_KEY` defaults to `false`. Local development may set it to `true`; tests enable it in `application-test.yml`. Do not enable it in staging or production.
3. **Verification resend invalidates older unused verification links** before issuing a new one, so only the latest link remains usable.
4. Added `.env.example` without real credentials and documented the safe key configuration.
5. This source handoff excludes `.env`, `.git`, `.idea`, and `target/` build output.

## OAuth / Google sign-in status — not configured

The API does **not** currently implement Google OAuth2/OIDC login. It includes `spring-boot-starter-oauth2-resource-server`, which validates KULHA-issued JWT bearer tokens. It does not include `spring-boot-starter-oauth2-client`, a Google `ClientRegistration`, `oauth2Login()`, an OIDC account-linking model, or an OAuth success/callback handler. There are no Google client settings in the supplied environment configuration.

Consequently, the API endpoint `/oauth2/authorization/google` is not currently provided by this backend. A frontend Google button that redirects there will not complete a Google login against this API.

This matches the current accepted decision in `docs/adr/002-identity-provider.md`: the pilot uses first-party email/password authentication and KULHA issues its own short-lived RS256 JWTs. That ADR explicitly says it is not OAuth2/OIDC login. If Google sign-in is a requirement, the architecture decision must be revised and the complete flow must be implemented together: Google OIDC Authorization Code, state/nonce validation, PKCE at the BFF handoff, verified-email handling, persistent provider-subject linking, first-party token issuance, one-time code exchange, callback allowlisting, and integration tests. Merely adding a client id/secret or the OAuth2 client dependency is not sufficient.

## Findings to resolve before public production

### High priority

- **Google login UI/backend mismatch:** remove/disable the Google button until the end-to-end OIDC flow exists, or implement the flow described above. Do not point it at an endpoint that this API does not provide.
- **Persistent key custody:** provision the Base64 DER RSA key pair from a secret manager in staging/production. Keep the private key out of Git, images, logs, CI artifacts and `.env` uploads. Current simple key configuration supports one active key; coordinated key rotation will invalidate access tokens signed with the previous key. Add a multi-key/JWKS rotation design before needing zero-downtime rotation.
- **Rate limiting is in-memory and per-instance:** acceptable only for a single-instance pilot. It resets on restart and is not shared across replicas. Replace it with shared Redis-backed rate limiting before scaling or exposing auth endpoints broadly.
- **Reverse-proxy client IP:** the IP limiter currently uses `HttpServletRequest.getRemoteAddr()`. Configure the trusted reverse proxy to strip client-supplied forwarding headers and set trusted forwarded headers; do not blindly trust arbitrary `X-Forwarded-For` from the public internet.

### Medium priority

- **Email delivery has no durable outbox/retry:** SMTP failures are logged by `SmtpEmailSender` and not retried. The API can accept a registration/reset request even when delivery fails. Add an outbox and bounded retries/DLQ before depending on email for production account recovery.
- **CSRF/BFF boundary must remain explicit:** the API disables CSRF because it is stateless and expects bearer tokens. Keep tokens server-side in the Next.js BFF and use secure HttpOnly/SameSite cookies plus CSRF/Origin protection at the BFF. Do not move refresh tokens into browser localStorage or cookie-authenticated API requests without revisiting CSRF.
- **No complete offers/reservations/payment API is present in this archive yet.** Current backend modules cover identity, merchant onboarding/admin review and audit; the marketplace transactions described by the product plan are not implemented here.

## Credential hygiene note

The uploaded source archive contained a local `.env` file, although Git confirms `.env` is ignored and is not tracked in the repository history. This output ZIP omits it. If any values in that uploaded `.env` are real or reused outside local development, rotate them—especially SMTP credentials, database credentials and the bootstrap administrator password. Do not upload or commit `.env` again.

## Verification status

- Java 21 is available in the review environment.
- Maven wrapper could not download Maven 3.10.0 in this environment, so a fresh `mvnw clean verify` could not be run here.
- The uploaded archive contained Surefire XML reports for 113 tests with zero recorded failures/errors, but those are pre-existing reports from the supplied build, not a fresh test execution by this review.
- New Java source is checked separately with `javac` against the dependencies embedded in the provided Spring Boot executable JAR where possible; full Maven/CI verification remains required after you extract the project.
