package ma.kulha.identity.application;

/** Wrong email or password, unknown account or disabled account. The caller is never told which. */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
