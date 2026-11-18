package ma.kulha.offer.interfaces.rest;

import ma.kulha.offer.application.MerchantNotApprovedException;
import ma.kulha.offer.application.MerchantNotFoundException;
import ma.kulha.offer.application.OfferLimitExceededException;
import ma.kulha.offer.application.IdempotencyConflictException;
import ma.kulha.offer.application.OfferNotFoundException;
import ma.kulha.offer.domain.model.InvalidOfferDataException;
import ma.kulha.offer.domain.model.InvalidOfferStateException;
import ma.kulha.offer.domain.model.InvalidOfferWindowException;
import ma.kulha.offer.domain.model.LockedOfferFieldsException;
import ma.kulha.offer.domain.model.StaleOfferVersionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {MerchantOfferController.class, AdminOfferController.class, PublicOfferController.class})
class OfferExceptionHandler {
    private static final HttpStatusCode UNPROCESSABLE_ENTITY = HttpStatusCode.valueOf(422);
    @ExceptionHandler({OfferNotFoundException.class, MerchantNotFoundException.class})
    ResponseEntity<ProblemDetail> notFound(RuntimeException e) { return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage()); }
    @ExceptionHandler({MerchantNotApprovedException.class})
    ResponseEntity<ProblemDetail> forbidden(RuntimeException e) { return problem(HttpStatus.FORBIDDEN, "Forbidden", e.getMessage()); }
    @ExceptionHandler({StaleOfferVersionException.class, OfferLimitExceededException.class, IdempotencyConflictException.class})
    ResponseEntity<ProblemDetail> conflict(RuntimeException e) { return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage()); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalidRequest(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        boolean publicOfferRequest = e.getParameter().getContainingClass() == PublicOfferController.class;
        HttpStatusCode status = publicOfferRequest ? HttpStatus.BAD_REQUEST : UNPROCESSABLE_ENTITY;
        String title = publicOfferRequest ? "Bad request" : "Unprocessable entity";
        String detail = publicOfferRequest ? "The nearby-search request is invalid."
                : "One or more offer fields are invalid.";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("fieldErrors", fieldErrors);
        problem.setProperty("code", publicOfferRequest ? "INVALID_REQUEST" : "VALIDATION_FAILED");
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(InvalidOfferWindowException.class)
    ResponseEntity<ProblemDetail> invalidWindow(InvalidOfferWindowException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(UNPROCESSABLE_ENTITY, e.getMessage());
        problem.setTitle("Unprocessable entity");
        problem.setProperty("fieldErrors", e.fieldErrors());
        problem.setProperty("code", "VALIDATION_FAILED");
        return ResponseEntity.status(UNPROCESSABLE_ENTITY).body(problem);
    }
    @ExceptionHandler({LockedOfferFieldsException.class, InvalidOfferStateException.class, InvalidOfferDataException.class})
    ResponseEntity<ProblemDetail> unprocessable(RuntimeException e) { return problem(UNPROCESSABLE_ENTITY, "Unprocessable entity", e.getMessage()); }
    private ResponseEntity<ProblemDetail> problem(HttpStatusCode status, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail); p.setTitle(title);
        p.setProperty("code", status.value() == 404 ? "NOT_FOUND" : status.value() == 409 ? "CONFLICT" : "INVALID_REQUEST");
        return ResponseEntity.status(status).body(p);
    }
}
