package ma.kulha.merchant.interfaces.rest;

import ma.kulha.merchant.application.EmailNotVerifiedException;
import ma.kulha.merchant.application.MerchantAlreadyExistsException;
import ma.kulha.merchant.application.MerchantNotFoundException;
import ma.kulha.merchant.domain.model.InvalidMerchantStateException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {MerchantController.class, AdminMerchantController.class})
class MerchantExceptionHandler {

    @ExceptionHandler(MerchantNotFoundException.class)
    ProblemDetail notFound() {
        return problem(HttpStatus.NOT_FOUND, "Merchant not found", "Merchant not found.");
    }

    @ExceptionHandler(MerchantAlreadyExistsException.class)
    ProblemDetail alreadyExists() {
        return problem(HttpStatus.CONFLICT, "Merchant already exists", "This account already has a merchant.");
    }

    @ExceptionHandler(EmailNotVerifiedException.class)
    ProblemDetail emailNotVerified() {
        return problem(HttpStatus.FORBIDDEN, "Email not verified", "Verify your email address first.");
    }

    @ExceptionHandler(InvalidMerchantStateException.class)
    ProblemDetail invalidState(InvalidMerchantStateException e) {
        return problem(HttpStatus.CONFLICT, "Action not allowed", e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
