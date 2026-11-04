# ADR 003: Google sign-in through the Next.js BFF

Date: 2026-11-01
Status: Accepted (product correction requested by the founder)

## Context

Google sign-in is part of the intended KULHA! account experience and was omitted from the initial delivery plan. The existing API-issued KULHA access JWT and rotating refresh-token session remain the application session; Google is an additional proof-of-identity method, not the issuer of KULHA API credentials.

## Decision

- Use Google's OpenID Connect authorization-code flow with PKCE (S256), `state`, and `nonce`.
- The Next.js BFF is the confidential OAuth client. The client secret, code verifier, state cookie and nonce stay server-side.
- The BFF signs a short-lived, HttpOnly, SameSite=Lax state cookie using the shared BFF secret, validates the returned state, exchanges the code server-side, and forwards only the ID token and nonce to the API.
- The API validates the ID-token signature against Google's JWKS and checks issuer, audience, expiry, `email_verified`, and nonce. It does not trust profile data supplied by the browser.
- Persist `(provider, subject)` as a unique external identity. Never use email as the provider's stable identifier.
- A verified Google email may be linked to an existing active account with the same normalized email. Disabled accounts cannot sign in or be reactivated through Google. New accounts receive only `CUSTOMER`.
- **Pre-hijacking rule.** If the existing account's email was never verified, whoever registered it with a password has not proven they own the mailbox. Before linking, the account's password is replaced by an unusable random one, all its refresh tokens are revoked, and its unused verification and reset links are cancelled. An already-verified account keeps its password. Confirming an email address likewise revokes every session that existed before the confirmation.
- A unique-constraint collision (two simultaneous first sign-ins, or an account already linked to a different Google subject) is retried once and otherwise answered with the normal 401, never a 500.
- The API returns the existing KULHA token pair. The BFF stores it only in the existing HttpOnly cookies. Google tokens are not stored or exposed to browser JavaScript.
- Require the existing shared BFF secret for the internal Google-login endpoint and apply the public-auth rate limit.

## Configuration

- Web: `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, optional `GOOGLE_REDIRECT_URI`.
- API: matching `GOOGLE_OAUTH_CLIENT_ID`.
- Both services: the same non-empty `KULHA_BFF_SHARED_SECRET`.
- Register the exact callback URI with Google Cloud. Never expose the client secret as a `NEXT_PUBLIC_*` variable.

## Consequences and verification

- Email/password login, Argon2id, KULHA JWT validation, refresh-token rotation and role authorization remain unchanged.
- Tests must cover valid/invalid issuer, audience, signature, expiry, nonce, unverified email, account linking, disabled accounts, callback state tampering, PKCE and token-cookie handoff.
- Google sign-in is not operational until Google Cloud credentials and matching environment configuration are supplied.
