package ma.kulha.notification.infrastructure.messaging;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import ma.kulha.notification.application.NotificationOutbox;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates each opted-in pickup reminder once, in the same transaction as its outbox event. */
@Component
@Profile("!test")
class PickupReminderScheduler {
    private final JdbcClient jdbc;
    private final NotificationOutbox outbox;

    PickupReminderScheduler(JdbcClient jdbc, NotificationOutbox outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    @Scheduled(fixedDelayString = "${app.notification.reminder.poll-ms:60000}")
    @Transactional
    void dispatchDueReminders() {
        List<DueReminder> due = jdbc.sql("""
                SELECT r.id AS reservation_id, r.customer_id, r.reference, r.merchant_name,
                       COALESCE(p.locale, 'fr') AS locale
                FROM reservation.reservations r
                LEFT JOIN notification.notification_preferences np ON np.user_id = r.customer_id
                LEFT JOIN identity.user_preferences p ON p.user_id = r.customer_id
                WHERE r.status = 'CONFIRMED'
                  AND r.pickup_start > now()
                  AND COALESCE(np.pickup_reminders, TRUE) = TRUE
                  AND r.pickup_start - (COALESCE(np.reminder_minutes_before, 60) * interval '1 minute') <= now()
                  AND NOT EXISTS (
                      SELECT 1 FROM notification.pickup_reminder_dispatches d
                      WHERE d.reservation_id = r.id
                  )
                ORDER BY r.pickup_start
                LIMIT 100
                FOR UPDATE OF r SKIP LOCKED
                """)
                .query((rs, row) -> new DueReminder(
                        rs.getObject("reservation_id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("reference"), rs.getString("merchant_name"),
                        rs.getString("locale")))
                .list();

        for (DueReminder reminder : due) {
            UUID notificationId = UUID.randomUUID();
            int inserted = jdbc.sql("""
                    INSERT INTO notification.pickup_reminder_dispatches (reservation_id, notification_id)
                    VALUES (:reservationId, :notificationId)
                    ON CONFLICT (reservation_id) DO NOTHING
                    """)
                    .param("reservationId", reminder.reservationId())
                    .param("notificationId", notificationId)
                    .update();
            if (inserted == 0) continue;
            Locale locale = Locale.forLanguageTag(reminder.locale());
            outbox.enqueue(reminder.customerId(), "PICKUP_REMINDER",
                    localized(locale, "Votre retrait approche", "Pickup starts soon", "اقترب موعد الاستلام"),
                    localized(locale, "Votre panier chez " + reminder.merchantName() + " sera bientôt prêt.",
                            "Your basket at " + reminder.merchantName() + " will be ready soon.",
                            "ستكون سلتك لدى " + reminder.merchantName() + " جاهزة قريباً."),
                    localized(locale, "Afficher mon code", "Show my code", "عرض رمز الاستلام"),
                    "/reservations/" + reminder.reference());
        }
    }

    private static String localized(Locale locale, String fr, String en, String ar) {
        return switch (locale.getLanguage()) {
            case "en" -> en;
            case "ar" -> ar;
            default -> fr;
        };
    }

    private record DueReminder(UUID reservationId, UUID customerId, String reference,
                               String merchantName, String locale) {}
}
