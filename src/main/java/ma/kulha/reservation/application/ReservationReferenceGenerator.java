package ma.kulha.reservation.application;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
@Component
class ReservationReferenceGenerator {
    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMdd").withZone(ZoneId.of("Africa/Casablanca"));
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    ReservationReferenceGenerator(Clock clock) { this.clock = clock; }
    String pickupCode() {
        char[] value = new char[6];
        for (int i = 0; i < value.length; i++) value[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        return new String(value);
    }
    String pickupQrToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static String hashPickupQrToken(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform.", impossible);
        }
    }
    String reference(String pickupCode) { return "KLH-" + DAY.format(clock.instant()) + "-" + pickupCode; }
}
