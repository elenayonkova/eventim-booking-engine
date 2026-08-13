package com.eventim.booking.engine.booking.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.eventim.booking.engine.booking.service.ConflictException;
import com.eventim.booking.engine.booking.service.ExternalServiceException;
import com.eventim.booking.engine.booking.service.NotFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(NotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(404, "Not Found", exception.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> conflict(ConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(409, "Conflict", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException exception) {
        String message = validationMessage(exception);
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(400, "Bad Request", message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> invalidArgument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(400, "Bad Request", exception.getMessage()));
    }

    @ExceptionHandler(ExternalServiceException.class)
    public ResponseEntity<ErrorResponse> externalService(ExternalServiceException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.of(503, "Service Unavailable", exception.getMessage()));
    }

    private String validationMessage(MethodArgumentNotValidException exception) {
        StringBuilder message = new StringBuilder();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            if (message.length() > 0) {
                message.append("; ");
            }
            message.append(error.getField())
                    .append(" ")
                    .append(error.getDefaultMessage());
        }
        return message.toString();
    }
}
