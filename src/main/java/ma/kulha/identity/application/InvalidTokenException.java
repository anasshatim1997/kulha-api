package ma.kulha.identity.application;

/** The token is unknown, already used or expired. The caller is never told which. */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException() {
        super("Invalid or expired token");
    }
}
