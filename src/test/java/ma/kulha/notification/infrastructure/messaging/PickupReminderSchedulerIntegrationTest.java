package ma.kulha.notification.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import ma.kulha.identity.application.IdentityApi;
import ma.kulha.identity.domain.model.User;
import ma.kulha.notification.application.NotificationOutbox;
import ma.kulha.reservation.application.ReservationReminderApi;
import ma.kulha.reservation.domain.model.Reservation;
import ma.kulha.reservation.domain.repository.ReservationRepository;
import ma.kulha.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PickupReminderSchedulerIntegrationTest extends AbstractApiIntegrationTest {
    @Autowired ReservationReminderApi reminderApi;
    @Autowired NotificationOutbox outbox;
    @Autowired IdentityApi identities;
    @Autowired ReservationRepository reservations;

    @Test
    void dispatchesOnlyDueOptedInRemindersAndIsIdempotent() {
        User dueCustomer = createUser("reminder-due@example.com", true);
        User notDueCustomer = createUser("reminder-not-due@example.com", true);
        User disabledCustomer = createUser("reminder-disabled@example.com", true);
        Instant now = Instant.now();

        identities.updateUserPreferences(dueCustomer.id(), new IdentityApi.UserPreferences(null, null, "en"));
        identities.updateUserPreferences(notDueCustomer.id(), new IdentityApi.UserPreferences(null, null, "fr"));
        identities.updateUserPreferences(disabledCustomer.id(), new IdentityApi.UserPreferences(null, null, "ar"));

        UUID dueReservation = insertConfirmedReservation(dueCustomer, "KLH-R-DUE", now.plusSeconds(1800), now);
        UUID notDueReservation = insertConfirmedReservation(notDueCustomer, "KLH-R-NOTDUE", now.plusSeconds(1800), now);
        UUID disabledReservation = insertConfirmedReservation(disabledCustomer, "KLH-R-DISABLED", now.plusSeconds(600), now);

        preference(dueCustomer.id(), true, 60);
        preference(notDueCustomer.id(), true, 15);
        preference(disabledCustomer.id(), false, 60);

        PickupReminderScheduler scheduler = new PickupReminderScheduler(reminderApi, outbox, identities);
        scheduler.dispatchDueReminders();

        assertThat(dispatchCount(dueReservation)).isEqualTo(1);
        assertThat(dispatchCount(notDueReservation)).isZero();
        assertThat(dispatchCount(disabledReservation)).isZero();
        assertThat(notificationCount(dueCustomer.id())).isEqualTo(1);
        assertThat(notificationTitle(dueCustomer.id())).isEqualTo("Pickup starts soon");

        scheduler.dispatchDueReminders();
        assertThat(dispatchCount(dueReservation)).isEqualTo(1);
        assertThat(notificationCount(dueCustomer.id())).isEqualTo(1);
    }

    private UUID insertConfirmedReservation(User customer, String reference, Instant pickupStart, Instant now) {
        UUID id = UUID.randomUUID();
        Reservation reservation = Reservation.pending(id, reference, customer.id(), UUID.randomUUID(), UUID.randomUUID(),
                "Reminder basket", "OTHER", "Boulangerie Atlas", "12 rue Atlas", "Casablanca", null,
                pickupStart, pickupStart.plusSeconds(3600), "REMINDER1", 1, 500, now).confirm(now);
        reservations.insert(reservation);
        return id;
    }

    private void preference(UUID userId, boolean enabled, int minutesBefore) {
        jdbc.sql("""
                INSERT INTO notification.notification_preferences
                    (user_id, pickup_reminders, reminder_minutes_before, email_enabled)
                VALUES (:userId, :enabled, :minutes, FALSE)
                """).param("userId", userId).param("enabled", enabled).param("minutes", minutesBefore).update();
    }

    private long dispatchCount(UUID reservationId) {
        return jdbc.sql("SELECT count(*) FROM notification.pickup_reminder_dispatches WHERE reservation_id=:id")
                .param("id", reservationId).query(Long.class).single();
    }

    private long notificationCount(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM notification.outbox_events WHERE aggregate_id=:userId")
                .param("userId", userId).query(Long.class).single();
    }

    private String notificationTitle(UUID userId) {
        return jdbc.sql("""
                SELECT payload->>'title' FROM notification.outbox_events
                WHERE aggregate_id=:userId ORDER BY occurred_at DESC LIMIT 1
                """).param("userId", userId).query(String.class).single();
    }
}
