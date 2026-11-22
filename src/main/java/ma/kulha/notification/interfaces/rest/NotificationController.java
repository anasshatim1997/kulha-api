package ma.kulha.notification.interfaces.rest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.kulha.shared.web.PageResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import ma.kulha.identity.application.IdentityApi;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class NotificationController {
    private final JdbcClient jdbc;
    private final IdentityApi identity;

    NotificationController(JdbcClient jdbc, IdentityApi identity) {
        this.jdbc = jdbc;
        this.identity = identity;
    }

    @GetMapping("/api/v1/notifications")
    List<NotificationItem> list(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
            Authentication authentication) {
        UUID userId = userId(authentication);
        String sql = """
                SELECT id, type, title, message, action_label, action_url, created_at, read_at,
                       (created_at >= date_trunc('day', now())) AS is_today
                FROM notification.notifications
                WHERE user_id = :userId
                  AND (:unreadOnly = false OR read_at IS NULL)
                ORDER BY created_at DESC, id DESC
                LIMIT :limit OFFSET :offset
                """;
        return jdbc.sql(sql)
                .param("userId", userId)
                .param("unreadOnly", unreadOnly)
                .param("limit", size)
                .param("offset", Math.multiplyExact(page, size))
                .query((rs, row) -> new NotificationItem(
                        rs.getObject("id", UUID.class), rs.getString("type"),
                        rs.getString("title"), rs.getString("message"),
                        rs.getString("action_label"), rs.getString("action_url"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                        rs.getObject("read_at", java.time.OffsetDateTime.class) == null ? null
                                : rs.getObject("read_at", java.time.OffsetDateTime.class).toInstant(),
                        rs.getBoolean("is_today")))
                .list();
    }

    @GetMapping("/api/v1/notifications/unread-count")
    UnreadCount unreadCount(Authentication authentication) {
        long count = jdbc.sql("""
                SELECT count(*) FROM notification.notifications
                WHERE user_id = :userId AND read_at IS NULL
                """)
                .param("userId", userId(authentication))
                .query(Long.class).single();
        return new UnreadCount(count);
    }

    @PatchMapping("/api/v1/notifications/{id}/read")
    @Transactional
    void markRead(@PathVariable UUID id, Authentication authentication) {
        jdbc.sql("""
                UPDATE notification.notifications
                SET read_at = COALESCE(read_at, now())
                WHERE id = :id AND user_id = :userId
                """)
                .param("id", id)
                .param("userId", userId(authentication))
                .update();
    }

    @PostMapping("/api/v1/notifications/read-all")
    @Transactional
    void markAllRead(Authentication authentication) {
        jdbc.sql("""
                UPDATE notification.notifications
                SET read_at = now()
                WHERE user_id = :userId AND read_at IS NULL
                """)
                .param("userId", userId(authentication))
                .update();
    }

    @GetMapping("/api/v1/me/notification-preferences")
    @Transactional
    NotificationPreferences preferences(Authentication authentication) {
        UUID userId = userId(authentication);
        ensurePreferences(userId);
        return jdbc.sql("""
                SELECT reservation_confirmations, pickup_reminders, cancellations_and_status_changes,
                       nearby_baskets, reminder_minutes_before, email_enabled, sms_enabled
                FROM notification.notification_preferences WHERE user_id = :userId
                """)
                .param("userId", userId)
                .query((rs, row) -> new NotificationPreferences(
                        rs.getBoolean("reservation_confirmations"),
                        rs.getBoolean("pickup_reminders"),
                        rs.getBoolean("cancellations_and_status_changes"),
                        rs.getBoolean("nearby_baskets"),
                        rs.getInt("reminder_minutes_before"),
                        rs.getBoolean("email_enabled"), rs.getBoolean("sms_enabled")))
                .single();
    }

    @PutMapping("/api/v1/me/notification-preferences")
    @Transactional
    NotificationPreferences updatePreferences(@Valid @RequestBody NotificationPreferences request,
                                                Authentication authentication) {
        UUID userId = userId(authentication);
        jdbc.sql("""
                INSERT INTO notification.notification_preferences (
                    user_id, reservation_confirmations, pickup_reminders,
                    cancellations_and_status_changes, nearby_baskets,
                    reminder_minutes_before, email_enabled, sms_enabled, updated_at
                ) VALUES (
                    :userId, :confirmations, :reminders, :changes, :nearby,
                    :minutes, :email, :sms, now()
                )
                ON CONFLICT (user_id) DO UPDATE SET
                    reservation_confirmations = EXCLUDED.reservation_confirmations,
                    pickup_reminders = EXCLUDED.pickup_reminders,
                    cancellations_and_status_changes = EXCLUDED.cancellations_and_status_changes,
                    nearby_baskets = EXCLUDED.nearby_baskets,
                    reminder_minutes_before = EXCLUDED.reminder_minutes_before,
                    email_enabled = EXCLUDED.email_enabled,
                    sms_enabled = EXCLUDED.sms_enabled,
                    updated_at = now()
                """)
                .param("userId", userId)
                .param("confirmations", request.reservationConfirmations())
                .param("reminders", request.pickupReminders())
                .param("changes", request.cancellationsAndStatusChanges())
                .param("nearby", request.nearbyBaskets())
                .param("minutes", request.reminderMinutesBefore())
                .param("email", request.emailEnabled())
                .param("sms", request.smsEnabled())
                .update();
        return request;
    }


    @GetMapping("/api/v1/me/preferences")
    @Transactional
    UserPreferences userPreferences(
            @RequestParam(defaultValue = "fr") @Pattern(regexp = "fr|en|ar") String locale,
            Authentication authentication) {
        IdentityApi.UserPreferences preferences = identity.getUserPreferences(userId(authentication), locale);
        return new UserPreferences(preferences.preferredCity(), preferences.preferredDistrict(), preferences.locale());
    }

    @PutMapping("/api/v1/me/preferences")
    @Transactional
    UserPreferences updateUserPreferences(@Valid @RequestBody UserPreferences request,
                                          Authentication authentication) {
        IdentityApi.UserPreferences preferences = new IdentityApi.UserPreferences(
                request.preferredCity(), request.preferredDistrict(), request.locale());
        identity.updateUserPreferences(userId(authentication), preferences);
        return request;
    }

    private void ensurePreferences(UUID userId) {
        jdbc.sql("""
                INSERT INTO notification.notification_preferences (user_id)
                VALUES (:userId) ON CONFLICT (user_id) DO NOTHING
                """).param("userId", userId).update();
    }

    private static UUID userId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }

    public record UserPreferences(@Size(max = 80) String preferredCity,
                                  @Size(max = 100) String preferredDistrict,
                                  @NotNull @Pattern(regexp = "fr|en|ar") String locale) {}
    public record NotificationItem(UUID id, String type, String title, String message,
                                   String actionLabel, String actionUrl, Instant createdAt, Instant readAt, boolean isToday) {}
    public record UnreadCount(long count) {}
    public record NotificationPreferences(
            @NotNull Boolean reservationConfirmations,
            @NotNull Boolean pickupReminders,
            @NotNull Boolean cancellationsAndStatusChanges,
            @NotNull Boolean nearbyBaskets,
            @Min(15) @Max(1440) int reminderMinutesBefore,
            @NotNull Boolean emailEnabled,
            @NotNull @AssertFalse Boolean smsEnabled) {}
}
