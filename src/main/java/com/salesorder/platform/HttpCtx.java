package com.salesorder.platform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 请求上下文：控制器只通过这个对象读取已解析、已校验边界的输入。 */
public final class HttpCtx {
    public final String method;
    public final String path;
    public final String requestId;
    public final Map<String, String> query;
    public final Map<String, String> params;
    public final Map<String, String> headers;
    public final Map<String, Object> body;
    public Security.User user;

    public HttpCtx(String method, String path, String requestId,
                   Map<String, String> query, Map<String, String> params,
                   Map<String, String> headers, Map<String, Object> body) {
        this.method = method;
        this.path = path;
        this.requestId = requestId;
        this.query = query == null ? new LinkedHashMap<>() : query;
        this.params = params == null ? new LinkedHashMap<>() : params;
        this.headers = headers == null ? new LinkedHashMap<>() : headers;
        this.body = body == null ? new LinkedHashMap<>() : body;
    }

    public String q(String key) { return query.get(key); }
    public String q(String key, String fallback) { return query.getOrDefault(key, fallback); }
    public String param(String key) { return params.get(key); }
    public String header(String key) { return headers.get(key); }

    public String required(String key) {
        Object v = body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw Errors.validation("缺少必填字段: " + key);
        }
        return String.valueOf(v).trim();
    }

    public String optional(String key, String fallback) {
        Object v = body.get(key);
        return v == null || String.valueOf(v).isBlank() ? fallback : String.valueOf(v).trim();
    }

    public int intField(String key, int fallback) { return Json.integer(body.get(key), fallback); }

    public int requiredInt(String key) {
        int v = Json.integer(body.get(key), Integer.MIN_VALUE);
        if (v == Integer.MIN_VALUE || v <= 0) throw Errors.validation("字段 " + key + " 必须是正整数");
        return v;
    }

    public boolean boolField(String key, boolean fallback) { return Json.bool(body.get(key), fallback); }

    public List<Object> listField(String key) {
        Object v = body.get(key);
        if (v == null) return List.of();
        if (!(v instanceof List<?>)) throw Errors.validation("字段 " + key + " 必须是数组");
        return Json.asList(v);
    }

    public Map<String, Object> mapField(String key) {
        Object v = body.get(key);
        return v == null ? new LinkedHashMap<>() : Json.asMap(v);
    }
}
