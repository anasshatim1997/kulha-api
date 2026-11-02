# kulha-api

Backend of **KULHA!**, an anti-food-waste marketplace for Morocco: merchants publish unsold baskets, customers reserve them and pick them up.

This is a **modular monolith** (Spring Boot + PostgreSQL). Module boundaries follow DDD so a module can be extracted into its own service later, but for the pilot there is **one deployable application**. See `docs/adr/001-modular-monolith.md`.

> Status: Sprint 1 (foundation). Not production-ready. Never mark a build or test as passing unless you have seen the real result.

## Stack

| Area | Choice |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4.1.x (version pinned in `pom.xml`) |
| Build | Maven, wrapper included (`mvnw` / `mvnw.cmd`) |
| Database | PostgreSQL 16, schema managed by Flyway |
| Security | Spring Security, deny-by-default (self-issued JWT authentication arrives in Sprint 2) |
| Tests | JUnit 5, ArchUnit (module boundaries) |
| Local infra | Docker Compose, PostgreSQL only |
| CI | GitHub Actions (`.github/workflows/ci.yaml`) with a PostgreSQL service container |

Not in the project yet, on purpose: RabbitMQ, Redis and SMTP (Sprint 8), the payment provider (Sprint 9), Testcontainers and OpenAPI docs (when the first endpoints need them).

## Prerequisites

- JDK 21
- Docker: Docker Desktop, or Docker Engine inside WSL2
- Git
- IntelliJ IDEA Ultimate (recommended)

Check:

```powershell
java -version
docker --version
git --version
```

## Getting started (Windows PowerShell)

### 1. Create your `.env`

Create a file named `.env` in the project root. It is ignored by Git and must **never be committed**. Local values only:

```
DB_HOST=localhost
DB_PORT=5433
DB_NAME=kulha
DB_USER=postgres
DB_PASSWORD=your-local-password
```

Port 5433 is used because port 5432 is blocked or reserved on some Windows machines. Docker Compose reads this file automatically.

### 2. Start the database

```powershell
cd C:\dev\kulha-api
docker compose up -d db
docker compose ps
```

Wait until `db` shows `(healthy)`. If Docker runs inside WSL, prefix the commands with `wsl`.

If the password in `.env` is changed after the first start, the database keeps the old one. Reset the local data with `docker compose down -v`, then start it again.

### 3. Run the application

In IntelliJ: open the project, set the SDK to Java 21 (File > Project Structure), then create or edit the Spring Boot run configuration for `KulhaApiApplication` and set the environment variables:

```
DB_USER=postgres;DB_PASSWORD=your-local-password;DB_PORT=5433
```

`DB_HOST` and `DB_NAME` have defaults in `application.yml`. Run it. Flyway applies the migrations on startup.

Or from the terminal, after setting the variables for that PowerShell window:

```powershell
$env:DB_USER="postgres"
$env:DB_PASSWORD="your-local-password"
$env:DB_PORT="5433"
.\mvnw.cmd spring-boot:run
```

### 4. Check it

```powershell
curl http://localhost:8080/actuator/health
```

Expected: `{"groups":["liveness","readiness"],"status":"UP"}`. Any other URL returns 403, because everything is denied by default.

If health shows `DOWN`, the database is not reachable: check `docker compose ps`.

## Configuration

Everything comes from environment variables. Real values stay in `.env` (never in Git).

| Variable | Purpose | Default |
|---|---|---|
| `DB_HOST` | PostgreSQL host | `localhost` |
| `DB_PORT` | PostgreSQL port | `5432` in `application.yml`, `5433` in the local setup above |
| `DB_NAME` | Database name | `kulha` |
| `DB_USER` | Database user | none, required |
| `DB_PASSWORD` | Database password | none, required |

Never use production credentials on a developer machine.

## Tests

```powershell
.\mvnw.cmd clean verify
```

This needs the database running and the `DB_*` variables set in the same PowerShell window, because `KulhaApiApplicationTests` starts the whole application. CI provides its own PostgreSQL, so it does not depend on your machine.

To run only the architecture tests (no database needed):

```powershell
.\mvnw.cmd "-Dtest=ModuleBoundariesTest" test
```

`ModuleBoundariesTest` (ArchUnit) fails the build if:
- a `domain` package depends on Spring or JPA
- a module uses another module's internal packages (`domain.model`, `domain.repository`, `infrastructure`, `interfaces`)

Rules for tests going forward: every fixed bug gets a regression test, and a flaky test is diagnosed, not disabled.

Priority scenarios, added as the modules arrive:
- two customers reserve the last unit: only one succeeds
- a pickup code reused: the second use is refused
- a merchant cannot read another merchant's data
- an invalid or expired token is refused

## Continuous integration

`.github/workflows/ci.yaml` runs `./mvnw clean verify` on every push to `main` and on every pull request, on a GitHub-hosted Ubuntu machine with a temporary PostgreSQL 16 service. Check the **Actions** tab after each push and fix a red build before continuing. The password in that file belongs to a throwaway CI database and is not a real secret.

## Project structure

```
kulha-api/
  .github/workflows/ci.yaml
  docs/adr/                  # architecture decision records
  src/main/java/ma/kulha/
    KulhaApiApplication.java
    shared/security/         # SecurityConfig (deny by default, health open)
    identity/                # accounts, roles, email verification   (Sprint 2)
    merchant/                # merchant profile, admin approval      (Sprint 3)
    offer/                   # offers, publication rules             (Sprint 4-5)
    reservation/             # availability, orders, pickup code     (Sprint 6-7)
    notification/            # emails, outbox consumers              (Sprint 8)
    payment/                 # payment model, pay-at-pickup default  (Sprint 9)
    admin/                   # moderation, audit log                 (Sprint 10)
  src/main/resources/
    application.yml
    db/migration/            # Flyway: V1__init_schemas.sql, ...
  src/test/java/ma/kulha/
    architecture/            # ArchUnit boundary tests
  compose.yaml               # local PostgreSQL
  pom.xml
```

Planned layout inside each module (created when the module is built):

```
<module>/
  domain/model        # entities, value objects, rules (no Spring, no JPA)
  domain/event
  domain/repository   # interfaces
  application/        # use cases
  infrastructure/     # persistence, messaging
  interfaces/rest     # controllers
  interfaces/dto      # request and response objects
```

## Architecture rules

1. One PostgreSQL schema per module (`identity`, `merchant`, `offer`, `reservation`, `notification`, `payment`, `admin`). No cross-schema joins or foreign keys.
2. A module talks to another only through its public `application` API or a domain event, never through another module's repository or entities.
3. `domain` does not depend on Spring, JPA or any message broker.
4. DTOs are separate from persistence entities and domain objects.
5. The server is the source of truth for price, stock, payment, ownership and authorization. Never trust a client-supplied role, `merchantId`, price or status.
6. Stock is decremented atomically in the database transaction and never goes negative.

Rules 2 and 3 are enforced by ArchUnit. Availability and orders live in the same module (`reservation`) so that the stock decrement and the order creation share one database transaction.

## Database migrations

- Flyway only. Never edit a migration that has been applied; add a new one.
- Naming: `V<number>__<description>.sql`.
- Timestamps in UTC (`timestamptz`).
- Money in MAD. Decide between integer minor units and `numeric(12,2)` and record it in an ADR before the first price column.

## JWT signing key configuration

For local development, copy `.env.example` to `.env`. The example explicitly enables an ephemeral RSA key for local use only. A new local key is generated at each restart, so existing access tokens stop validating after a restart; users can sign in again or refresh using a valid refresh token.

For staging and production, leave `APP_SECURITY_JWT_ALLOW_EPHEMERAL_KEY=false` and inject a persistent RSA key pair from your secret manager:

- `APP_SECURITY_JWT_PRIVATE_KEY_BASE64`: Base64-encoded PKCS#8 DER private key
- `APP_SECURITY_JWT_PUBLIC_KEY_BASE64`: Base64-encoded X.509 DER public key
- `APP_SECURITY_JWT_KEY_ID`: stable, non-secret key identifier (the JWT `kid`)

Generate a 2048-bit or stronger RSA key pair with OpenSSL (keep the private key secure):

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out jwt-private.pem
openssl pkey -in jwt-private.pem -pubout -out jwt-public.pem
openssl pkcs8 -topk8 -nocrypt -in jwt-private.pem -outform DER | openssl base64 -A > jwt-private.b64
openssl pkey -pubin -in jwt-public.pem -outform DER | openssl base64 -A > jwt-public.b64
```

Store the contents of the two `.b64` files in your secret manager as the corresponding environment variables. Never commit the PEM or `.b64` key files. The API checks that the public and private keys match at startup. This version supports one active signing key; replacing it invalidates old access tokens.

## BFF client IP and authentication rate limits

The API accepts `X-Kulha-Client-IP` only when `X-Kulha-Bff-Secret` matches `KULHA_BFF_SHARED_SECRET`; comparisons use a constant-time byte comparison. Otherwise, rate limits use the servlet remote address. Set the same long random `KULHA_BFF_SHARED_SECRET` in the API and web environments.

The Next.js app reads the client address only from `X-Real-IP`, supplied by the trusted reverse proxy. Configure that proxy to **remove any incoming client-supplied `X-Real-IP` and overwrite it with the actual connecting client IP**; do not expose Next.js directly in a way that bypasses this proxy. Never derive the rate-limit identity from browser-supplied `X-Forwarded-For` or `X-Kulha-*` headers.

## SMTP configuration and timeouts

SMTP connections use bounded connect/read/write timeouts (5,000 / 8,000 / 8,000 ms by default). Override them with `MAIL_SMTP_CONNECTION_TIMEOUT`, `MAIL_SMTP_TIMEOUT`, and `MAIL_SMTP_WRITE_TIMEOUT` when needed. Local Mailpit may run without STARTTLS; **staging and production must set both `MAIL_SMTP_STARTTLS=true` and `MAIL_SMTP_STARTTLS_REQUIRED=true`** and configure a mail provider that supports STARTTLS. Server certificate identity verification is enabled.

## Google OAuth / OIDC sign-in

Google sign-in is implemented as a BFF-mediated OIDC authorization-code flow with PKCE. The Next.js server owns the client secret, callback, signed state cookie, PKCE verifier and nonce. The API independently validates the Google ID-token signature against Google's JWKS, issuer, audience, expiry, verified-email claim and nonce before creating or linking an identity. Provider subjects are stored in `identity.external_identities`; email/password authentication and the existing KULHA refresh-token session remain unchanged.

Configure `GOOGLE_OAUTH_CLIENT_ID` in the API and the matching `GOOGLE_CLIENT_ID` plus `GOOGLE_CLIENT_SECRET` in the web server environment. Register the exact web callback URI (default local URI: `http://localhost:3000/api/auth/google/callback`) in Google Cloud. Both services must share the same non-empty `KULHA_BFF_SHARED_SECRET`. The API rejects direct Google-login requests without that secret. Google sign-in returns 503 until the client ID and BFF secret are configured. Never expose the client secret as a `NEXT_PUBLIC_*` variable.

A Google-verified email can link to an existing active account with the same normalized email; disabled accounts are never reactivated. A new Google account receives only the standard `CUSTOMER` role. Provider access/refresh tokens are not persisted.

## Security baseline

- No secrets in Git, images or logs. `.env` is in `.gitignore`. Check `git status` before every commit.
- Spring Security denies everything except `/actuator/health`. Open routes explicitly, one at a time.
- Do not expose actuator or management endpoints publicly in staging or production.
- Never store card data.
- Do not log passwords, tokens, cookies or unnecessary personal data.
- Dependency vulnerabilities are reviewed at each sprint start and before the pilot. Overrides of managed versions must be commented in `pom.xml` and removed when a Spring Boot release includes the fix.

## Git workflow

- Commit to `main` while the team has one developer. CI runs on every push; fix a red build before continuing.
- Use a short-lived branch (`feat/KUL-xx-short-name`) for risky changes.
- Commits: imperative and short (`Add offer publication rule`).
- Never commit `.env`. Check `git status` before every commit.

## Decisions

Architecture decisions are recorded in `docs/adr/`:

- `001-modular-monolith.md`
- `002-identity-provider.md`

## Definition of Done

- Acceptance criteria written and met, including at least one error case
- Authorization checked, with negative tests
- Tests pass in CI; migrations included
- Deployed to staging and accepted by the product owner
- README and ADRs updated when needed
- No known critical regression left silently

## Roadmap (backend)

| Sprint | Backend focus |
|---|---|
| 1 | Repo, CI, PostgreSQL, health endpoint, module skeleton, ArchUnit, ADRs |
| 2 | Accounts, email verification, roles, authorization |
| 3 | Merchant profile, admin approval |
| 4-5 | Offers: create, publish, disable, catalog |
| 6-7 | Reservation, atomic stock, pickup code |
| 8 | Outbox, RabbitMQ, SMTP, Redis (rate limits, cache) |
| 9 | Payment (pay-at-pickup unless a provider is approved) |
| 10-12 | Admin and audit, security hardening, backup and restore, release candidate |

## Out of scope for the pilot

Microservice deployments, Kubernetes, native mobile app, delivery, wallet or loyalty, AI features.

## Confidentiality

Private repository. Confidential to the founders and project team.
## Account and shop-owner onboarding

Customer and shop-owner accounts use the same email-verified identity flow. A verified user submits a business profile through `POST /api/v1/merchants/apply`; the application starts in `PENDING` and the owner receives merchant permissions only after an administrator approves it. Rejected applications may be corrected through `PUT /api/v1/merchants/me` and return to `PENDING`.

Verification and password-reset links place their single-use token in the URL fragment (`#token=...`). The browser fragment is not sent in HTTP requests; the web client reads it into memory and removes it from the address bar before the user explicitly confirms the action. HTML emails render these links as branded action buttons, while the plain-text alternative does not print the token URL.
