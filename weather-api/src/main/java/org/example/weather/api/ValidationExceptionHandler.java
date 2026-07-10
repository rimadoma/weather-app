package org.example.weather.api;

import jakarta.validation.ConstraintViolationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

// Must outrank GenericExceptionHandler's catch-all: without an explicit
// order, @ControllerAdvice beans are consulted in registration order and
// the first one with any applicable handler wins, regardless of
// specificity -- so an unordered Exception.class handler could shadow this.
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ValidationExceptionHandler extends ResponseEntityExceptionHandler {

    // Generated *Api interfaces carry a class-level @Validated, which
    // validates request params via an AOP proxy that throws this raw
    // exception type -- Spring's default resolvers translate the newer
    // HandlerMethodValidationException to 400 automatically, but not this.
    // That is, without this a page=-1 param would return 500.
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<String> handleConstraintViolation(ConstraintViolationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.getMessage());
    }
}
