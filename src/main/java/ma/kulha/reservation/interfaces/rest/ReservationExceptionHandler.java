package ma.kulha.reservation.interfaces.rest;
import ma.kulha.reservation.application.InvalidReservationRequestException;
import ma.kulha.reservation.application.ReservationConflictException;
import ma.kulha.reservation.application.ReservationNotFoundException;
import ma.kulha.reservation.application.ReservationUnavailableException;
import ma.kulha.reservation.domain.model.InvalidReservationStateException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
@RestControllerAdvice(assignableTypes = ReservationController.class)
class ReservationExceptionHandler {
    @ExceptionHandler(ReservationNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound() {
        return problem(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "Reservation not found.");
    }
    @ExceptionHandler(ReservationUnavailableException.class)
    ResponseEntity<ProblemDetail> unavailable(ReservationUnavailableException e) {
        return problem(HttpStatus.CONFLICT, "RESERVATION_UNAVAILABLE", e.getMessage());
    }
    @ExceptionHandler(ReservationConflictException.class)
    ResponseEntity<ProblemDetail> conflict(ReservationConflictException e) {
        return problem(HttpStatus.CONFLICT, "RESERVATION_CONFLICT", e.getMessage());
    }
    @ExceptionHandler(InvalidReservationStateException.class)
    ResponseEntity<ProblemDetail> invalidState(InvalidReservationStateException e) {
        return problem(HttpStatus.CONFLICT, "INVALID_RESERVATION_STATE", e.getMessage());
    }
    @ExceptionHandler(InvalidReservationRequestException.class)
    ResponseEntity<ProblemDetail> invalidRequest(InvalidReservationRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_RESERVATION_REQUEST", e.getMessage());
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> invalidArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_RESERVATION_REQUEST", e.getMessage());
    }
    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(status.getReasonPhrase());
        body.setProperty("code", code);
        return ResponseEntity.status(status).body(body);
    }
}
