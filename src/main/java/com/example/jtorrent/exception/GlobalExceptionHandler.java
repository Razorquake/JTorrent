package com.example.jtorrent.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(TorrentExceptions.TorrentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTorrentNotFound(TorrentExceptions.TorrentNotFoundException ex) {
        logClientError("Torrent not found", ex);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(HttpStatus.NOT_FOUND.value(), ex.getMessage(), LocalDateTime.now()));
    }

    @ExceptionHandler(TorrentExceptions.TorrentAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleTorrentAlreadyExists(TorrentExceptions.TorrentAlreadyExistsException ex) {
        logClientError("Torrent already exists", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(HttpStatus.CONFLICT.value(), ex.getMessage(), LocalDateTime.now()));
    }

    @ExceptionHandler(TorrentExceptions.TorrentNotActiveException.class)
    public ResponseEntity<ErrorResponse> handleTorrentNotActive(TorrentExceptions.TorrentNotActiveException ex) {
        logClientError("Torrent not active", ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), ex.getMessage(), LocalDateTime.now()));
    }

    @ExceptionHandler(TorrentExceptions.MaxConcurrentDownloadsException.class)
    public ResponseEntity<ErrorResponse> handleMaxConcurrentDownloads(TorrentExceptions.MaxConcurrentDownloadsException ex) {
        logClientError("Max concurrent downloads reached", ex);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ErrorResponse(HttpStatus.TOO_MANY_REQUESTS.value(), ex.getMessage(), LocalDateTime.now()));
    }

    @ExceptionHandler(TorrentExceptions.InvalidMagnetLinkException.class)
    public ResponseEntity<ErrorResponse> handleInvalidMagnetLink(TorrentExceptions.InvalidMagnetLinkException ex) {
        logClientError("Invalid magnet link", ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), ex.getMessage(), LocalDateTime.now()));
    }

    @ExceptionHandler(TorrentExceptions.TorrentFileException.class)
    public ResponseEntity<ErrorResponse> handleTorrentFileException(TorrentExceptions.TorrentFileException ex) {
        log.error("Torrent file error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), ex.getMessage(), LocalDateTime.now()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        logClientError("Illegal argument", ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), ex.getMessage(), LocalDateTime.now()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex) {
        logClientError("Illegal state", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(HttpStatus.CONFLICT.value(), ex.getMessage(), LocalDateTime.now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ValidationErrorResponse(HttpStatus.BAD_REQUEST.value(),
                        "Validation failed", errors, LocalDateTime.now()));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingServletRequestPart(MissingServletRequestPartException ex) {
        logClientError("Missing multipart request part", ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(),
                        "Missing required multipart part: " + ex.getRequestPartName(),
                        LocalDateTime.now()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "An unexpected error occurred: " + ex.getMessage(), LocalDateTime.now()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("Upload rejected – file exceeds maximum allowed size: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(new ErrorResponse(
                        HttpStatus.CONTENT_TOO_LARGE.value(),
                        "Uploaded file exceeds the maximum allowed size. "
                                + "Check spring.servlet.multipart.max-file-size in application.yaml.",
                        LocalDateTime.now()));
    }

    private void logClientError(String message, Exception ex) {
        log.warn("{}: {}", message, ex.getMessage());
        if (log.isDebugEnabled()) {
            log.debug("Client error details", ex);
        }
    }

    public record ErrorResponse(int status, String message, LocalDateTime timestamp) {}

    public record ValidationErrorResponse(int status, String message,
                                          Map<String, String> errors, LocalDateTime timestamp) {}
}
