package ma.kulha.offer.domain.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record OfferEventEnvelope(UUID eventId, String type, int schemaVersion, Instant occurredAt,
                                 UUID aggregateId, String correlationId, Map<String, Object> payload) { }
