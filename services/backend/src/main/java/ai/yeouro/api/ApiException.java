package ai.yeouro.api;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    public final HttpStatus status;
    public final String code;
    public ApiException(HttpStatus status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public static ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
    public static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "A valid, unexpired trip token is required.");
    }
    public static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "This token does not permit that action.");
    }
}
