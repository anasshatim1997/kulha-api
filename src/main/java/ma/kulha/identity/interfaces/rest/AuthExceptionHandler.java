package ma.kulha.identity.interfaces.rest;

import ma.kulha.identity.application.InvalidCredentialsException;
import ma.kulha.identity.application.InvalidRefreshTokenException;
import ma.kulha.identity.application.InvalidTokenException;
import ma.kulha.identity.application.GoogleSignInUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AuthController.class)
class AuthExceptionHandler {

    @ExceptionHandler(InvalidTokenException.class)
    ProblemDetail invalidToken() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "The link is invalid or has expired.");
        problem.setTitle("Invalid token");
        return problem;
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail invalidCredentials() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        problem.setTitle("Authentication failed");
        return problem;
    }

    @ExceptionHandler(GoogleSignInUnavailableException.class)
    ProblemDetail googleSignInUnavailable() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "Google sign-in is not configured.");
        problem.setTitle("Sign-in unavailable");
        return problem;
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    ProblemDetail invalidRefreshToken() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "The session has expired. Please log in again.");
        problem.setTitle("Authentication failed");
        return problem;
    }
}
