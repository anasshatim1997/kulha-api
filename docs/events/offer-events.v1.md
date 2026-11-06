# Offer events — schema version 1

Delivery in M4 is in-process through Spring's `ApplicationEventPublisher`, registered after the state transaction commits. There is no RabbitMQ, Redis, outbox table or broker publisher in M4.

## Envelope

```json
{
  "eventId": "uuid",
  "type": "OfferPublished",
  "schemaVersion": 1,
  "occurredAt": "2026-11-05T12:00:00Z",
  "aggregateId": "offer-uuid",
  "correlationId": "request-correlation-id-or-null",
  "payload": {
    "offerId": "offer-uuid",
    "merchantId": "merchant-uuid",
    "status": "PUBLISHED",
    "availableUnits": 8
  }
}
```

## Event types

- `OfferPublished`: after a draft/unpublished offer has committed as `PUBLISHED`.
- `OfferUnpublished`: after a published offer has committed as `UNPUBLISHED`.
- `OfferSuspended`: after an admin suspension or merchant-suspension cascade has committed as `SUSPENDED`.
- `OfferExpired`: after the expiry job has committed the `EXPIRED` transition.

The merchant module publishes an application event (`MerchantSuspended`) after its merchant-status transaction commits. The offer module consumes it, locks and re-reads each published offer in a separate transaction, writes the system-actor audit row with the offer transition, then publishes the corresponding `OfferSuspended` envelope after that transaction commits.

Payloads intentionally exclude title, description, merchant contact details, customer data and other personal or food-safety text. Money is not carried in the event envelope; consumers use `OfferApi.snapshot` for current server-owned values.

## Compatibility

Consumers must ignore unknown payload fields and reject unsupported `schemaVersion` values. A future breaking payload change increments the version and uses a new event schema document. M9 is responsible for durable outbox delivery and broker retry semantics.
