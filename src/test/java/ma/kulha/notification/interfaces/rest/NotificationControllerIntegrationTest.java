package ma.kulha.notification.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import ma.kulha.identity.domain.model.User;
import ma.kulha.identity.domain.repository.UserRepository;
import ma.kulha.notification.application.NotificationOutbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class NotificationControllerIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired NotificationOutbox notificationOutbox;

    private MockMvc mvc;
    private User owner;
    private User other;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.sql("DELETE FROM notification.outbox_events").update();
        jdbc.sql("DELETE FROM identity.users").update();
        owner = createUser("notifications-owner@example.com");
        other = createUser("notifications-other@example.com");
    }

    @Test
    void notificationInboxOnlyReturnsTheAuthenticatedUsersRecords() throws Exception {
        UUID ownId = insertNotification(owner.id(), "RESERVATION_CONFIRMED", "Your basket is confirmed");
        insertNotification(other.id(), "RESERVATION_CANCELLED", "Another customer's cancellation");

        mvc.perform(get("/api/v1/notifications").with(jwt().jwt(token -> token.subject(owner.id().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ownId.toString()))
                .andExpect(jsonPath("$[0].title").value("Your basket is confirmed"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void unreadCountAndMarkReadAreScopedToTheAuthenticatedUser() throws Exception {
        UUID notificationId = insertNotification(owner.id(), "PICKUP_REMINDER", "Pickup soon");

        mvc.perform(get("/api/v1/notifications/unread-count")
                        .with(jwt().jwt(token -> token.subject(owner.id().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        mvc.perform(patch("/api/v1/notifications/{id}/read", notificationId)
                        .with(jwt().jwt(token -> token.subject(other.id().toString()))))
                .andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT read_at IS NOT NULL FROM notification.notifications WHERE id = :id")
                .param("id", notificationId).query(Boolean.class).single()).isFalse();

        mvc.perform(patch("/api/v1/notifications/{id}/read", notificationId)
                        .with(jwt().jwt(token -> token.subject(owner.id().toString()))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/notifications/unread-count")
                        .with(jwt().jwt(token -> token.subject(owner.id().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void markAllReadOnlyChangesTheCurrentUsersNotifications() throws Exception {
        UUID ownId = insertNotification(owner.id(), "GENERAL", "Own update");
        UUID otherId = insertNotification(other.id(), "GENERAL", "Other update");

        mvc.perform(post("/api/v1/notifications/read-all")
                        .with(jwt().jwt(token -> token.subject(owner.id().toString()))))
                .andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT read_at IS NOT NULL FROM notification.notifications WHERE id = :id")
                .param("id", ownId).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT read_at IS NOT NULL FROM notification.notifications WHERE id = :id")
                .param("id", otherId).query(Boolean.class).single()).isFalse();
    }

    @Test
    void notificationPreferencesAreCreatedWithDefaultsAndPersistUpdates() throws Exception {
        mvc.perform(get("/api/v1/me/notification-preferences")
                        .with(jwt().jwt(token -> token.subject(owner.id().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationConfirmations").value(true))
                .andExpect(jsonPath("$.pickupReminders").value(true))
                .andExpect(jsonPath("$.nearbyBaskets").value(false))
                .andExpect(jsonPath("$.reminderMinutesBefore").value(60));

        String body = """
                {
                  "reservationConfirmations": true,
                  "pickupReminders": false,
                  "cancellationsAndStatusChanges": true,
                  "nearbyBaskets": true,
                  "reminderMinutesBefore": 30,
                  "emailEnabled": true,
                  "smsEnabled": false
                }
                """;
        mvc.perform(put("/api/v1/me/notification-preferences")
                        .with(jwt().jwt(token -> token.subject(owner.id().toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pickupReminders").value(false))
                .andExpect(jsonPath("$.reminderMinutesBefore").value(30));

        mvc.perform(get("/api/v1/me/notification-preferences")
                        .with(jwt().jwt(token -> token.subject(owner.id().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nearbyBaskets").value(true))
                .andExpect(jsonPath("$.reminderMinutesBefore").value(30));
    }

    @Test
    void notificationPreferencesRejectUnsupportedReminderIntervals() throws Exception {
        String body = """
                {
                  "reservationConfirmations": true,
                  "pickupReminders": true,
                  "cancellationsAndStatusChanges": true,
                  "nearbyBaskets": false,
                  "reminderMinutesBefore": 7,
                  "emailEnabled": true,
                  "smsEnabled": false
                }
                """;
        mvc.perform(put("/api/v1/me/notification-preferences")
                        .with(jwt().jwt(token -> token.subject(owner.id().toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void notificationEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/notification-preferences")).andExpect(status().isUnauthorized());
    }


    @Test
    void reservationNotificationWritesInboxAndEmailEventsToTheTransactionalOutbox() {
        notificationOutbox.enqueue(owner.id(), "RESERVATION_CONFIRMED", "Reservation confirmed",
                "Your basket is booked.", "View reservation", "/reservations/TEST-REF");

        assertThat(jdbc.sql("""
                SELECT count(*) FROM notification.outbox_events
                WHERE aggregate_id = :userId
                  AND event_type = 'notification.created'
                """).param("userId", owner.id()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM notification.outbox_events
                WHERE event_type = 'email.requested'
                  AND payload ->> 'recipient' = :email
                """).param("email", owner.email()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void disabledEmailPreferencesKeepInboxEventsButSuppressOptionalEmailEvents() {
        jdbc.sql("""
                INSERT INTO notification.notification_preferences (user_id, email_enabled)
                VALUES (:userId, false)
                """).param("userId", owner.id()).update();

        notificationOutbox.enqueue(owner.id(), "PICKUP_REMINDER", "Pickup soon",
                "Your basket is nearly ready.", "Show my code", "/reservations/TEST-REF");

        assertThat(jdbc.sql("""
                SELECT count(*) FROM notification.outbox_events
                WHERE aggregate_id = :userId AND event_type = 'notification.created'
                """).param("userId", owner.id()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM notification.outbox_events
                WHERE event_type = 'email.requested'
                  AND payload ->> 'recipient' = :email
                """).param("email", owner.email()).query(Long.class).single()).isZero();
    }

    @Test
    void firstUserPreferencesVisitUsesTheCurrentLocaleAndKeepsTheSavedChoice() throws Exception {
        mvc.perform(get("/api/v1/me/preferences?locale=ar")
                        .with(jwt().jwt(token -> token.subject(owner.id().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value("ar"));

        mvc.perform(get("/api/v1/me/preferences?locale=en")
                        .with(jwt().jwt(token -> token.subject(owner.id().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value("ar"));
    }

    private User createUser(String email) {
        User user = User.register(email, passwords.encode("a-long-enough-password"), "Test User", null, Instant.now());
        users.insert(user);
        return user;
    }

    private UUID insertNotification(UUID userId, String type, String title) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO notification.notifications (id, user_id, type, title, message)
                VALUES (:id, :userId, :type, :title, 'Test notification message')
                """)
                .param("id", id).param("userId", userId).param("type", type).param("title", title).update();
        return id;
    }
}
