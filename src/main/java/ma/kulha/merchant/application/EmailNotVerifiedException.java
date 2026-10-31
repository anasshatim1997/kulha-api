package ma.kulha.merchant.application;

public class EmailNotVerifiedException extends RuntimeException {

    public EmailNotVerifiedException() {
        super("Email address not verified");
    }
}
