# ADR 001: Modular monolith for the pilot

Date: 2026-10-29
Status: Accepted

## Context

KULHA! is built by two people, only one of them technical. The Technical Guide
describes a microservices architecture. The delivery plan (v1.1) defers
microservices because one technical person cannot run many pipelines, many
databases and distributed transactions, especially on the reservation flow,
which must never oversell stock.

## Decision

Build one Spring Boot application with one module per business domain:
identity, merchant, offer, reservation, notification, payment, admin.

- One PostgreSQL schema per module. No cross-schema joins or foreign keys.
- Modules communicate only through their public `application` layer or through
  domain events. A module never uses another module's repositories, entities or
  infrastructure code.
- The `domain` layer does not depend on Spring or JPA.
- These rules are enforced by ArchUnit tests (`ModuleBoundariesTest`) that run
  in CI.
- Availability and orders live in the same module (`reservation`) so that the
  stock decrement and the order creation happen in a single database
  transaction.

## Consequences

- One codebase, one pipeline and one deployment for the pilot.
- No distributed transactions on the critical reservation flow.
- A module can be extracted into its own service later, if load or team size
  requires it, because the boundaries are already enforced.
- The boundaries only hold while the ArchUnit tests stay green. A red
  architecture test is fixed, never disabled.
