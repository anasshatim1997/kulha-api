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

This change provisions the services and the durable outbox table. It does not yet switch existing email sends to asynchronous delivery; that is the next implementation slice and must include publisher confirms, idempotent consumers, bounded retries and dead-letter handling before being relied on for production.
