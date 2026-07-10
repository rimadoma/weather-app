package org.example.weather.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

// Runs last: this is the true catch-all, so more specific @ExceptionHandler
// advice (e.g. ValidationExceptionHandler) must be given higher precedence
// or this would shadow it too (see the note there).
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class GenericExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GenericExceptionHandler.class);

    // Catch-all for genuine bugs/broken preconditions (e.g. querying a
    // materialised view before it's been refreshed): logged here so the
    // detail isn't lost, but the client only ever sees a bare 500 -- no
    // error codes/diagnostic detail by design (docs/requirements.md, iteration 12).
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Something went wrong");
    }
}
