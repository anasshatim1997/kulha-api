package ma.kulha.shared.web;

import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns standard Spring MVC errors (validation, bad JSON, wrong method...) into
 * Problem Details responses (RFC 9457) without leaking internals.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {
}
