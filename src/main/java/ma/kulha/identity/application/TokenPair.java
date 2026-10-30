package ma.kulha.identity.application;

public record TokenPair(String accessToken, String refreshToken, long expiresInSeconds) {
}
