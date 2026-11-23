package ma.kulha.reservation.application;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReservationReminderApiService implements ReservationReminderApi {
    private final JdbcClient jdbc;

    ReservationReminderApiService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PickupReminderCandidate> findConfirmedUpcoming(Instant now, Instant pickupBefore,
            Instant afterPickupStart, UUID afterReservationId, int limit) {
        return jdbc.sql("""
                SELECT id, customer_id, reference, merchant_name, pickup_start
                FROM reservation.reservations
                WHERE status = 'CONFIRMED'
                  AND pickup_start > :now
                  AND pickup_start <= :pickupBefore
                  AND (CAST(:afterPickupStart AS timestamptz) IS NULL
                       OR pickup_start > :afterPickupStart
                       OR (pickup_start = :afterPickupStart AND id > :afterReservationId))
                ORDER BY pickup_start, id
                LIMIT :limit
                """)
                .param("now", db(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("pickupBefore", db(pickupBefore), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("afterPickupStart", db(afterPickupStart), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("afterReservationId", afterReservationId)
                .param("limit", Math.clamp(limit, 1, 500))
                .query((rs, row) -> new PickupReminderCandidate(rs.getObject("id", UUID.class),
                        rs.getObject("customer_id", UUID.class), rs.getString("reference"),
                        rs.getString("merchant_name"),
                        rs.getObject("pickup_start", OffsetDateTime.class).toInstant()))
                .list();
    }

    private static OffsetDateTime db(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
