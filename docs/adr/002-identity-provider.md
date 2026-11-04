# ADR 002: Authentication with self-issued JWT

Date: 2026-10-29
Status: Accepted (technical decision by the technical founder). Extended by ADR-003 (Google sign-in).

## Context

The API needs login, short-lived access tokens, refresh tokens, email
verification and password recovery. Three options were considered:

1. Keycloak (self-hosted identity provider)
2. A managed identity provider
3. The API issues and validates its own JWTs

Keycloak and a managed provider are rejected for the pilot: the technical
founder prefers to keep authentication inside the API, with no extra service
to run.

## Decision

The API issues and validates its own JWTs. There is no external identity
provider. The API is built so that it only depends on *validating* a JWT
(Spring Security resource server). If the issuer is moved out later (Spring
Authorization Server or an external provider), the rest of the API does not
change.

### Tokens

- Login: `POST /api/v1/auth/login` with email and password, over HTTPS only.
  Returns an access token (JWT) and a refresh token.
- Access token: signed with RS256 (or ES256). The accepted algorithm is an
  explicit allowlist; `alg=none` and algorithm substitution are refused.
- Claims: `iss`, `aud`, `sub`, `exp`, `iat`, `jti`, plus `kid` in the header.
  The validator checks signature, issuer, audience and expiry.
- Lifetime: 5 to 15 minutes.
- No personal data, no business data and no card data inside the JWT.
- Refresh token: opaque random value, single use, rotating. Only its hash is
  stored in PostgreSQL. Reuse of an old refresh token revokes the whole token
  family. Logout and password change revoke the user's refresh tokens.
- Signing keys: the private key is never in Git. It is injected by environment
  variable or secret store. `kid` allows key rotation.

### Passwords and account flows

- Passwords hashed with Argon2id and a unique salt. No fast or reversible
  hashing.
- Email verification and password reset use opaque, single-use tokens with a
  short expiry. Only the hash is stored. Responses never reveal whether an
  email is registered.
- After a successful reset, existing sessions and refresh tokens are revoked.
- Login, verification and reset endpoints are rate limited.

### Web and mobile storage

- Web: tokens are not kept in `localStorage`. The Next.js server holds them and
  the browser gets an HttpOnly, Secure, SameSite cookie. CSRF protection
  applies to cookie-authenticated mutations.
- The cookie model is decided and documented in Sprint 2 before the login
  screen is built.

### Authorization

- The token proves who the caller is. Roles and ownership are checked on the
  server on every request, against PostgreSQL.
- A `merchantId`, role or price sent by the client, or read from a claim
  without a database check, is never trusted.
- Required negative tests: wrong signature, wrong issuer, wrong audience,
  expired token, reused refresh token, a customer calling a merchant
  endpoint, a merchant reading another merchant's data.

## Consequences

- We own every account feature ourselves: registration, verification, reset,
  refresh rotation, revocation, rate limiting and, before public launch, MFA
  for administrators. This is more code and more security responsibility for
  one technical person than an external provider would be.
- This is first-party authentication, not OAuth 2.0 / OpenID Connect. The
  technical addendum asks for Authorization Code with PKCE; this ADR
  knowingly deviates from it for the pilot. That deviation is accepted by the
  technical founder and must be revisited before public launch.
- Verification and reset emails go through the notification module once
  RabbitMQ and SMTP exist (Sprint 8). Until then they are sent in a minimal
  way for local and staging use only.
- Rate limiting uses a simple local mechanism until Redis arrives in
  Sprint 8. Login and reset endpoints must not be exposed publicly before
  shared rate limiting is in place.

## Open items

- Choose the web session model (server-side session or BFF) in Sprint 2.
- Decide the MFA approach for administrators (TOTP) before public launch.
- Revisit the deviation from OAuth 2.0 / OIDC before the pilot goes public.
