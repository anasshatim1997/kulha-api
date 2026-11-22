package ma.kulha.notification.application;

import java.time.Instant;
import java.util.UUID;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;
import ma.kulha.notification.domain.NotificationEvent;
import ma.kulha.identity.application.IdentityApi;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Writes notification and optional email events in the caller's business transaction. */
@Component
public class NotificationOutbox {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final EmailOutbox emailOutbox;
    private final IdentityApi identities;

    public NotificationOutbox(JdbcClient jdbc, ObjectMapper objectMapper, EmailOutbox emailOutbox, IdentityApi identities) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.emailOutbox = emailOutbox;
        this.identities = identities;
    }

    @Transactional
    public UUID enqueue(UUID userId, String type, String title, String message,
                        String actionLabel, String actionUrl) {
        UUID eventId = writeEvent(userId, type, title, message, actionLabel, actionUrl);
        if (shouldEmail(userId, type)) {
            identities.emailOf(userId).ifPresent(email ->
                    emailOutbox.enqueue(email, EmailTemplates.notification(title, message)));
        }
        return eventId;
    }

    @Transactional(readOnly = true)
    public Map<UUID, PickupReminderPreferences> findPickupReminderPreferences(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) return Map.of();
        return jdbc.sql("""
                SELECT user_id, pickup_reminders, reminder_minutes_before, email_enabled
                FROM notification.notification_preferences
                WHERE user_id IN (:ids)
                """).param("ids", userIds)
                .query((rs, row) -> Map.entry(rs.getObject("user_id", UUID.class),
                        new PickupReminderPreferences(rs.getBoolean("pickup_reminders"),
                                rs.getInt("reminder_minutes_before"), rs.getBoolean("email_enabled"))))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Transactional
    public boolean markPickupReminderDispatched(UUID reservationId) {
        return jdbc.sql("""
                INSERT INTO notification.pickup_reminder_dispatches (reservation_id)
                VALUES (:reservationId)
                ON CONFLICT (reservation_id) DO NOTHING
                """).param("reservationId", reservationId).update() == 1;
    }

    @Transactional
    public void enqueuePickupReminderBatch(List<PickupReminderDraft> reminders) {
        if (reminders == null || reminders.isEmpty()) return;
        Set<UUID> emailUserIds = reminders.stream().filter(PickupReminderDraft::emailEnabled)
                .map(PickupReminderDraft::userId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, String> emails = identities.findEmailsForNotifications(emailUserIds);
        for (PickupReminderDraft reminder : reminders) {
            writeEvent(reminder.userId(), "PICKUP_REMINDER", reminder.title(), reminder.message(),
                    reminder.actionLabel(), reminder.actionUrl());
            String email = reminder.emailEnabled() ? emails.get(reminder.userId()) : null;
            if (email != null) emailOutbox.enqueue(email,
                    EmailTemplates.notification(reminder.title(), reminder.message()));
        }
    }

    private UUID writeEvent(UUID userId, String type, String title, String message,
                            String actionLabel, String actionUrl) {
        UUID eventId = UUID.randomUUID();
        NotificationEvent event = new NotificationEvent(eventId, userId, type, title, message,
                actionLabel, actionUrl, Instant.now());
        try {
            String payload = objectMapper.writeValueAsString(event);
            jdbc.sql("""
                    INSERT INTO notification.outbox_events
                        (id, aggregate_type, aggregate_id, event_type, payload)
                    VALUES (:id, 'USER', :userId, 'notification.created', CAST(:payload AS jsonb))
                    """)
                    .param("id", eventId).param("userId", userId).param("payload", payload).update();
            return eventId;
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize notification event", exception);
        }
    }

    public record PickupReminderPreferences(boolean enabled, int minutesBefore, boolean emailEnabled) {}
    public record PickupReminderDraft(UUID userId, String title, String message,
                                      String actionLabel, String actionUrl, boolean emailEnabled) {}

    private boolean shouldEmail(UUID userId, String type) {
        return jdbc.sql("""
                SELECT COALESCE(p.email_enabled, TRUE)
                    AND CASE :type
                        WHEN 'RESERVATION_CONFIRMED' THEN COALESCE(p.reservation_confirmations, TRUE)
                        WHEN 'PICKUP_REMINDER' THEN COALESCE(p.pickup_reminders, TRUE)
                        WHEN 'RESERVATION_CANCELLED' THEN COALESCE(p.cancellations_and_status_changes, TRUE)
                        WHEN 'RESERVATION_COLLECTED' THEN COALESCE(p.cancellations_and_status_changes, TRUE)
                        WHEN 'RESERVATION_EXPIRED' THEN COALESCE(p.cancellations_and_status_changes, TRUE)
                        ELSE TRUE
                    END
                FROM notification.notification_preferences p
                WHERE p.user_id = :userId
                """)
                .param("userId", userId)
                .param("type", type)
                .query(Boolean.class)
                .optional()
                .orElse(true);
    }
}
