package com.salesorder.platform;

import java.util.LinkedHashMap;
import java.util.Map;

/** 类型化错误体系：客户端只会看到规范化错误结构，绝不暴露堆栈与内部细节。 */
public final class Errors {
    private Errors() {}

    public static final class AppException extends RuntimeException {
        public final int status;
        public final String code;

        public AppException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    public static AppException badRequest(String message) {
        return new AppException(400, "BAD_REQUEST", message);
    }

    public static AppException validation(String message) {
        return new AppException(422, "VALIDATION_FAILED", message);
    }

    public static AppException unauthorized(String message) {
        return new AppException(401, "UNAUTHORIZED", message);
    }

    public static AppException tokenExpired() {
        return new AppException(401, "TOKEN_EXPIRED", "访问令牌已过期，请刷新");
    }

    public static AppException forbidden(String message) {
        return new AppException(403, "FORBIDDEN", message);
    }

    public static AppException notFound(String message) {
        return new AppException(404, "NOT_FOUND", message);
    }

    public static AppException conflict(String message) {
        return new AppException(409, "CONFLICT", message);
    }

    public static AppException internal(String message) {
        return new AppException(500, "INTERNAL_ERROR", message);
    }

    public static Map<String, Object> payload(AppException e, String requestId) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", e.code);
        error.put("message", e.getMessage());
        error.put("requestId", requestId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        return body;
    }
}
