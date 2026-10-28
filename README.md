# kulha-api

Backend of **KULHA!**, an anti-food-waste marketplace for Morocco: merchants publish unsold baskets, customers reserve and pick them up.

This is a **modular monolith** (Spring Boot + PostgreSQL). Module boundaries follow DDD so that a module can be extracted into its own service later, but for the pilot there is **one deployable application**.

> Status: Sprint 1 (foundation). Nothing here is production-ready. Do not mark a build or test as passing unless you have seen the real result.

## Stack

| Area | Choice |
|---|---|
| Language / runtime | Java 21 (LTS) |
| Framework | Spring Boot (pinned in `pom.xml`) |
| Build | Maven (wrapper `./mvnw`) |
| Database | PostgreSQL 16, Flyway migrations |
| Security | Spring Security (OAuth2 resource server added in Sprint 2) |
| API docs | springdoc-openapi (`/swagger-ui.html`, `/v3/api-docs`) |
| Tests | JUnit 5, AssertJ, Testcontainers, ArchUnit |
| Local infra | Docker Compose (PostgreSQL only in Sprint 1) |

RabbitMQ, Redis, SMTP and the payment provider are added in Sprints 8-9. They are intentionally absent now.

## Prerequisites

- JDK 21
- Docker Desktop (WSL2 on Windows) or Docker Engine + Compose plugin
- IntelliJ IDEA Ultimate (Spring Boot, Database and Docker plugins are bundled)
- Git

Check:

```bash
java -version
docker --version
docker compose version
git --version
```

## Getting started

```bash
git clone <PRIVATE_REPOSITORY_URL> kulha-api
cd kulha-api
cp .env.example .env        # then edit values; never commit .env
docker compose up -d db
./mvnw clean verify
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Windows PowerShell: use `copy .env.example .env` and `mvnw.cmd`.

Verify the app is up:

```bash
curl http://localhost:8080/actuator/health
```

### IntelliJ IDEA Ultimate

1. **Open** the project folder (it detects Maven automatically).
2. **Project Structure → SDK**: Java 21.
3. Run configuration **Spring Boot** → main class `ma.kulha.KulhaApiApplication`.
   - Active profiles: `local`
   - Environment variables: load from `.env` (EnvFile plugin) or paste them in the run configuration.
4. **Database tool window → +** → PostgreSQL → `localhost:5432`, DB/user from `.env`.
5. Enable **annotation processing** only if you add a library that needs it.

## Configuration

All configuration comes from environment variables. Names only here; real values stay in `.env` (ignored by Git).

| Variable | Purpose | Example (fake) |
|---|---|---|
| `DB_HOST` | PostgreSQL host | `localhost` |
| `DB_PORT` | PostgreSQL port | `5432` |
| `DB_NAME` | Database name | `kulha` |
| `DB_USER` | Database user | `kulha` |
| `DB_PASSWORD` | Database password | `change-me` |
| `SPRING_PROFILES_ACTIVE` | Profile | `local` |

Profiles: `local` (developer machine), `staging`, `prod`. Never use production credentials locally.

## Project structure

```
kulha-api/
  src/main/java/ma/kulha/
    KulhaApiApplication.java
    shared/            # errors (Problem Details), ids, clock, correlation ID
    identity/          # accounts, roles, email verification        (S2)
    merchant/          # merchant profile, approval                 (S3)
    offer/             # offers, publication rules                  (S4-S5)
    reservation/       # availability, orders, pickup code          (S6-S7)
    notification/      # emails, outbox consumers                   (S8)
    payment/           # payment model (pay-at-pickup by default)   (S9)
    admin/             # moderation, audit log                      (S10)
  src/main/resources/
    application.yml
    application-local.yml
    db/migration/      # Flyway: V1__init_schemas.sql, ...
  src/test/java/ma/kulha/
    architecture/      # ArchUnit boundary tests
  compose.yaml
  .env.example
  .gitignore
  pom.xml
  README.md
```

Inside each module:

```
<module>/
  domain/model        # entities, value objects, rules (no Spring, no JPA)
  domain/event
  domain/repository   # interfaces
  application/        # use cases (command / query / service / port)
  infrastructure/     # persistence, messaging
  interfaces/rest     # controllers
  interfaces/dto      # request/response DTOs
```

## Architecture rules

1. **One PostgreSQL schema per module** (`identity`, `merchant`, `offer`, `reservation`, ...). No cross-schema joins or foreign keys.
2. A module talks to another only through its public `application` API or a domain event. Never through another module's repository or entities.
3. `domain` does not depend on Spring, JPA or any broker.
4. DTOs are separate from persistence entities and domain objects.
5. **The server is the source of truth** for price, stock, payment, ownership and authorization. Never trust a client-supplied role, `merchantId`, price or status.
6. Stock is decremented atomically in the database transaction. Stock never goes negative.
7. Rules 1-3 are enforced by ArchUnit tests in `src/test/java/ma/kulha/architecture`. A violation fails CI.

Decisions are recorded in `docs/adr/` (ADR 001: modular monolith, ADR 002: identity provider).

## Database migrations

- Flyway only. Never edit an applied migration; add a new one.
- Naming: `V<number>__<description>.sql`, one schema per module.
- Timestamps in UTC (`timestamptz`).
- Money in MAD, stored as integer minor units or `numeric(12,2)`. Pick one and document it in an ADR.

## Testing

```bash
./mvnw test              # unit tests
./mvnw clean verify      # unit + integration (Testcontainers needs Docker running)
```

Required before merge: build, lint/format, unit and integration tests pass in CI. Every fixed bug gets a regression test. A flaky test is diagnosed, not disabled.

Priority scenarios (added as the modules arrive):
- two customers reserve the last unit: only one succeeds
- pickup code reused: second use refused
- a merchant cannot read another merchant's data
- invalid or expired token refused

## Security baseline

- No secrets in Git, images or logs. `.env` is in `.gitignore`.
- Spring Security denies by default. Only `/actuator/health` (and later documented public routes) are open.
- Actuator and management endpoints must not be publicly exposed in staging/prod.
- No card data is ever stored.
- Do not log passwords, tokens, cookies or personal data that is not needed.

## Git workflow

- `main` is protected: pull request + green CI required.
- Branches: `feat/KUL-xx-short-name`, `fix/KUL-xx-short-name`.
- One ticket per PR, linked to its acceptance criteria.
- Commits: imperative, short (`Add offer publication rule`).

## Definition of Done

- Acceptance criteria written and met, including at least one error case
- Authorization checked, with negative tests
- Tests pass in CI; migrations included
- Deployed to staging and accepted by the product owner
- `README`, `.env.example` and ADRs updated if needed
- No known critical regression left silently

## Roadmap (backend)

| Sprint | Backend focus |
|---|---|
| S1 | Repo, CI, Postgres, health endpoint, module skeleton, ArchUnit |
| S2 | Accounts, email verification, roles, authorization |
| S3 | Merchant profile, admin approval |
| S4-S5 | Offers: create, publish, disable, catalog |
| S6-S7 | Reservation, atomic stock, pickup code |
| S8 | Outbox, RabbitMQ, SMTP, Redis (rate limits, cache) |
| S9 | Payment (pay-at-pickup unless a provider is approved) |
| S10-S12 | Admin/audit, security hardening, backup/restore, release candidate |

## Out of scope for the pilot

Microservice deployments, Kubernetes, native mobile app, delivery, wallet/loyalty, AI features.

## License / confidentiality

Private repository. Confidential to the founders and project team.
