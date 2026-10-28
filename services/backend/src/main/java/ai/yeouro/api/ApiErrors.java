package ai.yeouro.api;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiModels.Error> domain(ApiException ex) {
        return ResponseEntity.status(ex.status).body(new ApiModels.Error(ex.code, ex.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
                       MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiModels.Error> invalid(Exception ignored) {
        // Never reflect payloads: they may contain invite codes, credentials, or exact coordinates.
        return ResponseEntity.badRequest().body(new ApiModels.Error("INVALID_REQUEST", "Request fields are missing or invalid."));
    }
    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<ApiModels.Error> conflict(Exception ignored) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiModels.Error("CONCURRENT_UPDATE", "Refresh the trip and try again."));
    }
}
