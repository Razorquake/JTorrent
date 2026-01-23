package com.example.jtorrent.exception;

import com.example.jtorrent.dto.AddTorrentRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GlobalExceptionHandler Unit Tests")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("Should handle TorrentNotFoundException")
    void testHandleTorrentNotFound() {
        TorrentExceptions.TorrentNotFoundException ex = new TorrentExceptions.TorrentNotFoundException(1L);

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleTorrentNotFound(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().message()).contains("1");
        assertThat(response.getBody().timestamp()).isNotNull();
    }

    @Test
    @DisplayName("Should handle TorrentAlreadyExistsException")
    void testHandleTorrentAlreadyExists() {
        TorrentExceptions.TorrentAlreadyExistsException ex =
                new TorrentExceptions.TorrentAlreadyExistsException("hash-1");

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleTorrentAlreadyExists(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().message()).contains("hash-1");
    }

    @Test
    @DisplayName("Should handle TorrentNotActiveException")
    void testHandleTorrentNotActive() {
        TorrentExceptions.TorrentNotActiveException ex =
                new TorrentExceptions.TorrentNotActiveException(2L);

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleTorrentNotActive(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().message()).contains("2");
    }

    @Test
    @DisplayName("Should handle MaxConcurrentDownloadsException")
    void testHandleMaxConcurrentDownloads() {
        TorrentExceptions.MaxConcurrentDownloadsException ex =
                new TorrentExceptions.MaxConcurrentDownloadsException(3);

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleMaxConcurrentDownloads(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getBody().message()).contains("3");
    }

    @Test
    @DisplayName("Should handle InvalidMagnetLinkException")
    void testHandleInvalidMagnetLink() {
        TorrentExceptions.InvalidMagnetLinkException ex =
                new TorrentExceptions.InvalidMagnetLinkException("bad");

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleInvalidMagnetLink(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().message()).contains("bad");
    }

    @Test
    @DisplayName("Should handle TorrentFileException")
    void testHandleTorrentFileException() {
        TorrentExceptions.TorrentFileException ex =
                new TorrentExceptions.TorrentFileException("file error");

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleTorrentFileException(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().message()).contains("file error");
    }

    @Test
    @DisplayName("Should handle IllegalArgumentException")
    void testHandleIllegalArgument() {
        IllegalArgumentException ex = new IllegalArgumentException("bad arg");

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleIllegalArgument(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().message()).contains("bad arg");
    }

    @Test
    @DisplayName("Should handle IllegalStateException")
    void testHandleIllegalState() {
        IllegalStateException ex = new IllegalStateException("bad state");

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleIllegalState(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().message()).contains("bad state");
    }

    @Test
    @DisplayName("Should handle validation errors")
    void testHandleValidationErrors() throws Exception {
        Method method = ValidationTarget.class.getDeclaredMethod("submit", AddTorrentRequest.class);
        MethodParameter methodParameter = new MethodParameter(method, 0);

        AddTorrentRequest request = new AddTorrentRequest();
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(request, "request");
        bindingResult.addError(new FieldError("request", "magnetLink",
                "Magnet link or torrent file is required"));

        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(methodParameter, bindingResult);

        ResponseEntity<GlobalExceptionHandler.ValidationErrorResponse> response =
                handler.handleValidationErrors(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().errors()).containsEntry(
                "magnetLink", "Magnet link or torrent file is required");
    }

    @Test
    @DisplayName("Should handle generic exception")
    void testHandleGenericException() {
        Exception ex = new RuntimeException("boom");

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response = handler.handleGenericException(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().message()).contains("boom");
    }

    private static class ValidationTarget {
        void submit(AddTorrentRequest request) {
        }
    }
}
