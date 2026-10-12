package hotel;

import java.util.LinkedHashMap;
import java.util.Map;

// An error the client is meant to see: HTTP status, a stable code, a readable
// message and, for form problems, one message per field.
final class ApiException extends RuntimeException {

    final int status;
    final String code;
    final Map<String, String> fields;

    ApiException(int status, String code, String message) {
        this(status, code, message, Map.of());
    }

    ApiException(int status, String code, String message, Map<String, String> fields) {
        super(message);
        this.status = status;
        this.code = code;
        this.fields = fields;
    }

    static ApiException notFound(String what) {
        return new ApiException(404, "not_found", what + " was not found. It may have been deleted.");
    }

    static ApiException badRequest(String message) {
        return new ApiException(400, "bad_request", message);
    }

    static ApiException validation(Map<String, String> fields) {
        return new ApiException(422, "validation_failed", "Please correct the highlighted fields.", fields);
    }

    static ApiException conflict(String message, Map<String, String> fields) {
        return new ApiException(409, "conflict", message, fields);
    }

    static ApiException unavailable() {
        return new ApiException(503, "database_unavailable",
                "The database is not reachable right now. Your changes were not saved — please try again shortly.");
    }

    Map<String, Object> toBody() {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", getMessage());
        if (!fields.isEmpty()) {
            error.put("fields", fields);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        return body;
    }
}
