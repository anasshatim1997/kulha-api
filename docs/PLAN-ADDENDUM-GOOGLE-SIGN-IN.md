# KULHA! plan addendum — Google sign-in

Date: 2026-11-01
Status: Added to the MVP backlog at the founder's request

This addendum supplements (and does not replace) `KULHA_Plan.pdf` v1.1. Google sign-in was unintentionally omitted from the original plan.

## KUL-05A — Google sign-in (P0, size M, owner T)

As a customer, I want to create or access my KULHA! account with Google so that I can use a familiar sign-in method without managing another password.

### Acceptance criteria

- Google OIDC authorization-code flow with PKCE S256, one-time state, nonce, exact callback URI and server-side code exchange.
- Google ID token signature, issuer, audience, expiry, nonce and `email_verified` are validated by the API.
- Google subject is persisted as a unique external identity; email is not used as the provider identifier.
- A verified Google email links only to an existing active account with the same normalized email; disabled accounts cannot be reactivated. New accounts receive only CUSTOMER.
- The API issues the normal KULHA access/refresh pair; the Next.js BFF stores it only in HttpOnly cookies. Google client secret and all provider tokens stay server-side.
- Existing email/password registration, login, verification, Argon2id and refresh-token rotation continue to work unchanged.
- Tests cover callback state tampering, PKCE, invalid token claims, account linking, disabled accounts and cookie handoff.
- Local and staging configuration is documented; sign-in fails closed when credentials or the BFF secret are missing.

### Dependencies and sequencing

KUL-02 (reproducible application and CI foundation) remains the current Sprint 1 foundation task. KUL-05A belongs with Sprint 2 access and identity, alongside KUL-04/KUL-05; the implementation can be prepared now, but it is not accepted as complete until CI and the OAuth security tests pass with real Google Cloud test credentials.

### Decision record

See `docs/adr/003-google-oidc-sign-in.md`.
