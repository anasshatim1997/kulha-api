package ma.kulha.notification.infrastructure.messaging;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import ma.kulha.identity.application.IdentityApi;
import ma.kulha.notification.application.NotificationOutbox;
import ma.kulha.notification.application.NotificationOutbox.PickupReminderPreferences;
import ma.kulha.reservation.application.ReservationReminderApi;
import ma.kulha.reservation.application.ReservationReminderApi.PickupReminderCandidate;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates module-owned batch APIs; dispatch claims and outbox writes remain atomic. */
@Component
@Profile("!test")
class PickupReminderScheduler {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_DISPATCHES_PER_RUN = 100;
    private static final PickupReminderPreferences DEFAULT_PREFERENCES = new PickupReminderPreferences(true, 60);

    private final ReservationReminderApi reservations;
    private final NotificationOutbox notifications;
    private final IdentityApi identities;

    PickupReminderScheduler(ReservationReminderApi reservations, NotificationOutbox notifications,
                            IdentityApi identities) {
        this.reservations = reservations;
        this.notifications = notifications;
        this.identities = identities;
    }

    @Scheduled(fixedDelayString = "${app.notification.reminder.poll-ms:60000}")
    @Transactional
    void dispatchDueReminders() {
        Instant now = Instant.now();
        Instant pickupBefore = now.plus(Duration.ofDays(1));
        Instant cursorPickupStart = null;
        UUID cursorReservationId = null;
        int dispatched = 0;

        while (dispatched < MAX_DISPATCHES_PER_RUN) {
            List<PickupReminderCandidate> page = reservations.findConfirmedUpcoming(
                    now, pickupBefore, cursorPickupStart, cursorReservationId, PAGE_SIZE);
            if (page.isEmpty()) break;

            Set<UUID> userIds = page.stream().map(PickupReminderCandidate::customerId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Map<UUID, PickupReminderPreferences> preferences = notifications.findPickupReminderPreferences(userIds);
            Map<UUID, String> locales = identities.findLocales(userIds);

            for (PickupReminderCandidate candidate : page) {
                cursorPickupStart = candidate.pickupStart();
                cursorReservationId = candidate.reservationId();

                PickupReminderPreferences preference = preferences.getOrDefault(
                        candidate.customerId(), DEFAULT_PREFERENCES);
                if (!preference.enabled()
                        || candidate.pickupStart().minus(Duration.ofMinutes(preference.minutesBefore())).isAfter(now)) {
                    continue;
                }
                if (!notifications.markPickupReminderDispatched(candidate.reservationId())) continue;

                Locale locale = Locale.forLanguageTag(locales.getOrDefault(candidate.customerId(), "fr"));
                notifications.enqueue(candidate.customerId(), "PICKUP_REMINDER",
                        localized(locale, "Votre retrait approche", "Pickup starts soon", "اقترب موعد الاستلام"),
                        localized(locale, "Votre panier chez " + candidate.merchantName() + " sera bientôt prêt.",
                                "Your basket at " + candidate.merchantName() + " will be ready soon.",
                                "ستكون سلتك لدى " + candidate.merchantName() + " جاهزة قريباً."),
                        localized(locale, "Afficher mon code", "Show my code", "عرض رمز الاستلام"),
                        "/reservations/" + candidate.reference());
                dispatched++;
                if (dispatched >= MAX_DISPATCHES_PER_RUN) break;
            }

            if (page.size() < PAGE_SIZE) break;
        }
    }

    private static String localized(Locale locale, String fr, String en, String ar) {
        return switch (locale.getLanguage()) {
            case "en" -> en;
            case "ar" -> ar;
            default -> fr;
        };
    }
}
