package org.example.weather.api;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ValidationExceptionHandler {

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
