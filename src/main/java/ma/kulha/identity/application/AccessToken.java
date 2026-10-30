package ma.kulha.identity.application;

public record AccessToken(String value, long expiresInSeconds) {
}
