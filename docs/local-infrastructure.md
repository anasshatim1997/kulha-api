# Local infrastructure

The API uses PostgreSQL as its durable source of truth. RabbitMQ is used for asynchronous event delivery and Redis is used for shared ephemeral state such as distributed rate limits and short-lived caches.

## Start dependencies

Copy the environment template to `.env` (or set the variables in your shell), then run:

```powershell
docker compose up -d db mailpit rabbitmq redis
```

Local endpoints:

- PostgreSQL: `localhost:5433` (or `DB_PORT`)
- Mailpit SMTP: `localhost:1025`
- Mailpit UI: `http://localhost:8025`
- RabbitMQ AMQP: `localhost:5672`
- RabbitMQ management: `http://localhost:15672`
- Redis: `localhost:6379`

The local RabbitMQ defaults are `kulha` / `kulha-local`. Override them with `RABBITMQ_USER` and `RABBITMQ_PASSWORD`. Do not reuse these local credentials outside development.

The application accepts `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, and `REDIS_PASSWORD`.

## Notification processing

Business transactions write notification and email requests to `notification.outbox_events`. A scheduled relay publishes due events to the durable `kulha.events` exchange and marks them published only after a broker publisher confirmation. Unroutable events and negative acknowledgements are retried with bounded exponential backoff; events that exhaust the retry budget are marked dead-lettered in PostgreSQL.

- `kulha.notifications`: idempotently persists in-app inbox records.
- `kulha.emails`: sends transactional email with up to three SMTP attempts.
- `kulha.emails.dead` and `kulha.notifications.dead`: durable dead-letter queues for consumer failures.

Reservation confirmation, cancellation, collection and opted-in pickup reminders create in-app events. Reservation emails are added to the outbox only when the user's email and category preferences allow them. Account verification, password reset and merchant-decision emails also use the email outbox and are not suppressed by optional notification preferences.

The test profile intentionally uses the in-memory rate limiter and disables scheduled RabbitMQ consumers so integration tests do not require Redis or RabbitMQ. Local development uses Redis and RabbitMQ from this Compose file. Production still needs monitored dead-letter operations and SMTP/provider delivery monitoring before launch.
