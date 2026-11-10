package com.salesorder.platform;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 结构化 JSON 日志，请求 ID 贯穿全链路；不记录密码、令牌与隐私数据。 */
public final class Log {
    private Log() {}

    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();

    public static void bind(String requestId) { REQUEST_ID.set(requestId); }
    public static void clear() { REQUEST_ID.remove(); }

    public static String requestId() {
        String id = REQUEST_ID.get();
        return id == null ? "-" : id;
    }

    public static void info(String event, Object... kv) { emit("INFO", event, kv); }
    public static void warn(String event, Object... kv) { emit("WARN", event, kv); }
    public static void error(String event, Object... kv) { emit("ERROR", event, kv); }

    private static void emit(String level, String event, Object... kv) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", Instant.now().toString());
        line.put("level", level);
        line.put("event", event);
        line.put("requestId", requestId());
        for (int i = 0; i + 1 < kv.length; i += 2) {
            line.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        System.out.println(Json.stringify(line));
    }
}
