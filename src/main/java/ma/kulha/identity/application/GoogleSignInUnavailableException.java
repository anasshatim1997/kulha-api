package ma.kulha.identity.application;

public class GoogleSignInUnavailableException extends RuntimeException {
    public GoogleSignInUnavailableException() { super("Google sign-in is not configured"); }
}
