package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.TotpNotEnrolledException;
import com.mcortes.authcoremc.service.DuplicateIdentifierException;
import com.mcortes.authcoremc.service.InvalidCredentialsException;
import com.mcortes.authcoremc.service.InvalidTokenException;
import com.mcortes.authcoremc.service.TooManyAttemptsException;
import com.mcortes.authcoremc.service.UnsupportedProviderException;
import com.mcortes.authcoremc.service.UserNotFoundException;
import com.mcortes.authcoremc.service.WeakPasswordException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates domain/service exceptions into the uniform error shape from
 * docs/API.md. Every branch here is a deliberate, tested mapping — nothing
 * falls through to a generic 500 that would hide which rule was violated.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UnknownClientException.class)
    public ResponseEntity<ErrorResponse> handleUnknownClient(UnknownClientException e) {
        return error(HttpStatus.UNAUTHORIZED, "unknown_client", e.getMessage());
    }

    @ExceptionHandler(WeakPasswordException.class)
    public ResponseEntity<ErrorResponse> handleWeakPassword(WeakPasswordException e) {
        return error(HttpStatus.BAD_REQUEST, "weak_password", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, "invalid_request", e.getMessage());
    }

    @ExceptionHandler(DuplicateIdentifierException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateIdentifier(DuplicateIdentifierException e) {
        return error(HttpStatus.CONFLICT, "duplicate_identifier", e.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException e) {
        return error(HttpStatus.UNAUTHORIZED, "invalid_credentials", e.getMessage());
    }

    @ExceptionHandler(TooManyAttemptsException.class)
    public ResponseEntity<ErrorResponse> handleTooManyAttempts(TooManyAttemptsException e) {
        return error(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts", e.getMessage());
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidTokenException e) {
        return error(HttpStatus.BAD_REQUEST, "invalid_token", e.getMessage());
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "user_not_found", e.getMessage());
    }

    @ExceptionHandler(TotpNotEnrolledException.class)
    public ResponseEntity<ErrorResponse> handleTotpNotEnrolled(TotpNotEnrolledException e) {
        return error(HttpStatus.BAD_REQUEST, "totp_not_enrolled", e.getMessage());
    }

    @ExceptionHandler(UnsupportedProviderException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedProvider(UnsupportedProviderException e) {
        return error(HttpStatus.BAD_REQUEST, "unsupported_provider", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .orElse("Invalid request");
        return error(HttpStatus.BAD_REQUEST, "validation_error", message);
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message));
    }
}
