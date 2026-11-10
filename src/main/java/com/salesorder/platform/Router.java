package com.salesorder.platform;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 路由分发、输入边界解析、统一错误响应、CORS、安全头与静态资源。 */
public final class Router implements HttpHandler {
    public interface Handler {
        Object handle(HttpCtx ctx) throws Exception;
    }

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/health", "/ready", "/api/auth/login", "/api/auth/refresh");

    private record Route(String method, String[] segments, String[] roles, Handler handler) {}

    private final Config config;
    private final Security security;
    private final List<Route> routes = new ArrayList<>();

    public Router(Config config, Security security) {
        this.config = config;
        this.security = security;
    }

    public Router get(String path, Handler h, String... roles) { return add("GET", path, h, roles); }
    public Router post(String path, Handler h, String... roles) { return add("POST", path, h, roles); }
    public Router put(String path, Handler h, String... roles) { return add("PUT", path, h, roles); }
    public Router delete(String path, Handler h, String... roles) { return add("DELETE", path, h, roles); }

    private Router add(String method, String path, Handler h, String[] roles) {
        routes.add(new Route(method, path.split("/"), roles, h));
        return this;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String requestId = Ids.uuid().substring(0, 8);
        Log.bind(requestId);
        long start = System.currentTimeMillis();
        int status = 200;
        try {
            applyCommonHeaders(ex);
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            if ("OPTIONS".equals(method)) {
                status = 204;
                send(ex, 204, null);
                return;
            }
            if ("GET".equals(method) && !path.startsWith("/api/") && !PUBLIC_PATHS.contains(path)) {
                status = serveStatic(ex, path);
                return;
            }
            Route route = match(method, path);
            if (route == null) {
                status = 404;
                sendJson(ex, 404, Errors.payload(Errors.notFound("接口不存在: " + path), requestId));
                return;
            }
            HttpCtx ctx = buildCtx(ex, route, requestId);
            if (!PUBLIC_PATHS.contains(path)) {
                ctx.user = security.authenticate(ctx.header("authorization"));
                security.require(ctx, route.roles());
                // 优化：移动端权限边界 —— 手机端只保留「查看 + 审批」写权限，
                // 建单、改单、基础资料维护一律留在 PC 端，订单内容修改必须走变更单。
                if ("mobile".equalsIgnoreCase(ctx.header("x-client"))
                        && ("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method))
                        && !path.startsWith("/api/approvals/")) {
                    throw Errors.forbidden("手机端仅可查看与审批；创建/修改业务单据请在 PC 端办理，订单内容修改须走《销售订单变更及通知单》");
                }
            }
            Object result = route.handler().handle(ctx);
            if (result instanceof Resp resp) {
                status = resp.status;
                if (status == 204) send(ex, 204, null);
                else sendJson(ex, status, resp.body);
            } else {
                status = 200;
                sendJson(ex, 200, result);
            }
        } catch (Errors.AppException e) {
            status = e.status;
            sendJson(ex, e.status, Errors.payload(e, requestId));
        } catch (Exception e) {
            status = 500;
            Log.error("unhandled_error", "type", e.getClass().getSimpleName(), "message", String.valueOf(e.getMessage()));
            sendJson(ex, 500, Errors.payload(Errors.internal("服务内部错误，请联系管理员并提供 requestId"), requestId));
        } finally {
            Log.info("http_access", "method", ex.getRequestMethod(), "path", ex.getRequestURI().getPath(),
                    "status", status, "durationMs", System.currentTimeMillis() - start);
            Log.clear();
            ex.close();
        }
    }

    private Route match(String method, String path) {
        String[] parts = path.split("/");
        for (Route r : routes) {
            if (!r.method().equals(method) || r.segments().length != parts.length) continue;
            boolean ok = true;
            for (int i = 0; i < parts.length; i++) {
                String s = r.segments()[i];
                if (s.startsWith("{") && s.endsWith("}")) continue;
                if (!s.equals(parts[i])) { ok = false; break; }
            }
            if (ok) return r;
        }
        return null;
    }

    private HttpCtx buildCtx(HttpExchange ex, Route route, String requestId) throws IOException {
        Map<String, String> query = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null && !raw.isBlank()) {
            for (String pair : raw.split("&")) {
                int i = pair.indexOf('=');
                String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
                String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
                query.put(k, v);
            }
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : ex.getRequestHeaders().entrySet()) {
            if (!e.getValue().isEmpty()) headers.put(e.getKey().toLowerCase(), e.getValue().get(0));
        }
        Map<String, String> params = new LinkedHashMap<>();
        String[] actual = ex.getRequestURI().getPath().split("/");
        String[] pattern = route.segments();
        for (int i = 0; i < pattern.length; i++) {
            if (pattern[i].startsWith("{") && pattern[i].endsWith("}")) {
                params.put(pattern[i].substring(1, pattern[i].length() - 1), actual[i]);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if ("POST".equals(ex.getRequestMethod()) || "PUT".equals(ex.getRequestMethod())) {
            byte[] bytes;
            try (InputStream in = ex.getRequestBody()) { bytes = in.readNBytes(1024 * 1024); }
            if (bytes.length > 0) {
                String text = new String(bytes, StandardCharsets.UTF_8);
                Object parsed;
                try { parsed = Json.parse(text); }
                catch (Exception e) { throw Errors.badRequest("请求体不是合法 JSON"); }
                body = Json.asMap(parsed);
            }
        }
        return new HttpCtx(ex.getRequestMethod(), ex.getRequestURI().getPath(), requestId, query, params, headers, body);
    }

    private void applyCommonHeaders(HttpExchange ex) {
        Headers h = ex.getResponseHeaders();
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && config.corsOrigins.contains(origin)) {
            h.set("Access-Control-Allow-Origin", origin);
            h.set("Vary", "Origin");
            h.set("Access-Control-Allow-Credentials", "true");
        }
        h.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Request-Id, X-Client");
        h.set("Access-Control-Max-Age", "600");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "SAMEORIGIN");
        h.set("Referrer-Policy", "no-referrer");
        h.set("X-Request-Id", Log.requestId());
        h.set("Cache-Control", "no-store");
    }

    private void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private void send(HttpExchange ex, int status, byte[] bytes) throws IOException {
        if (status == 204 || bytes == null) {
            ex.sendResponseHeaders(status, -1);
        } else {
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
        }
    }

    private int serveStatic(HttpExchange ex, String path) throws IOException {
        String relative = "/".equals(path) || path.isBlank() ? "index.html" : path.substring(1);
        Path target = config.webDir.resolve(relative).normalize();
        if (!target.startsWith(config.webDir) || !Files.isRegularFile(target)) {
            sendJson(ex, 404, Errors.payload(Errors.notFound("页面不存在: " + path), Log.requestId()));
            return 404;
        }
        byte[] bytes = Files.readAllBytes(target);
        ex.getResponseHeaders().set("Content-Type", contentType(relative));
        send(ex, 200, bytes);
        return 200;
    }

    private static String contentType(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (n.endsWith(".json")) return "application/json; charset=utf-8";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    public List<String> routeSummary() {
        List<String> out = new ArrayList<>();
        for (Route r : routes) {
            out.add(r.method() + " " + String.join("/", r.segments())
                    + (r.roles().length == 0 ? "" : " [" + String.join(",", Arrays.asList(r.roles())) + "]"));
        }
        return out;
    }
}
