package ma.kulha.reservation.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import ma.kulha.reservation.domain.repository.ReservationIdempotencyRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcReservationIdempotencyRepository implements ReservationIdempotencyRepository {
    private final JdbcClient jdbc;

    JdbcReservationIdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lock(UUID customerId, String key) {
        String lockName = customerId + ":" + key;
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:lockName, 0))")
                .param("lockName", lockName)
                .query((rs, row) -> Boolean.TRUE)
                .single();
    }

    @Override
    public Optional<IdempotencyRecord> find(UUID customerId, String key) {
        return jdbc.sql("""
                SELECT request_hash, response_snapshot::text AS response_snapshot
                FROM reservation.idempotency_keys
                WHERE customer_id = :customer AND idempotency_key = :key
                """)
                .param("customer", customerId)
                .param("key", key)
                .query((rs, row) -> new IdempotencyRecord(
                        rs.getString("request_hash"), rs.getString("response_snapshot")))
                .optional();
    }

    @Override
    public void save(UUID customerId, String key, String requestHash, UUID reservationId, String responseSnapshot) {
        jdbc.sql("""
                INSERT INTO reservation.idempotency_keys
                    (customer_id, idempotency_key, request_hash, reservation_id, response_snapshot)
                VALUES (:customer, :key, :hash, :reservation, CAST(:response AS jsonb))
                """)
                .param("customer", customerId)
                .param("key", key)
                .param("hash", requestHash)
                .param("reservation", reservationId)
                .param("response", responseSnapshot)
                .update();
    }
}
