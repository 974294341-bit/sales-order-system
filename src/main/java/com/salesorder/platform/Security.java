package com.salesorder.platform;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 认证与授权：HS256 短时效访问令牌 + 服务端刷新令牌 + RBAC。刷新令牌只存服务端。 */
public final class Security {
    public static final String ADMIN = "ADMIN";
    public static final String BUSINESS = "BUSINESS";          // 业务部/市场部
    public static final String BUSINESS_MANAGER = "BUSINESS_MANAGER"; // 业务部负责人
    public static final String ENGINEERING = "ENGINEERING";    // 工程部
    public static final String PLANNING = "PLANNING";          // 计划部
    public static final String FINANCE = "FINANCE";            // 财务部
    public static final String WAREHOUSE = "WAREHOUSE";        // 仓库
    public static final String PURCHASING = "PURCHASING";      // 采购
    public static final String QUALITY = "QUALITY";            // 品质
    public static final String LEADER = "LEADER";              // 分管领导
    public static final String DOC_CONTROL = "DOC_CONTROL";    // 文控中心

    public static final List<String> ALL_ROLES = List.of(
            ADMIN, BUSINESS, BUSINESS_MANAGER, ENGINEERING, PLANNING, FINANCE,
            WAREHOUSE, PURCHASING, QUALITY, LEADER, DOC_CONTROL);

    public static final Map<String, String> ROLE_LABELS = Map.ofEntries(
            Map.entry(ADMIN, "系统管理员"),
            Map.entry(BUSINESS, "业务部/市场部"),
            Map.entry(BUSINESS_MANAGER, "业务部负责人"),
            Map.entry(ENGINEERING, "工程部"),
            Map.entry(PLANNING, "计划部"),
            Map.entry(FINANCE, "财务部"),
            Map.entry(WAREHOUSE, "仓库"),
            Map.entry(PURCHASING, "采购"),
            Map.entry(QUALITY, "品质"),
            Map.entry(LEADER, "分管领导"),
            Map.entry(DOC_CONTROL, "文控中心"));

    public static final class User {
        public final String username;
        public final String name;
        public final String role;

        public User(String username, String name, String role) {
            this.username = username;
            this.name = name;
            this.role = role;
        }

        public Map<String, Object> view() {
            return Json.obj("username", username, "name", name, "role", role,
                    "roleLabel", ROLE_LABELS.getOrDefault(role, role));
        }
    }

    private static final class RefreshSession {
        final String username;
        final long expiresAt;
        RefreshSession(String username, long expiresAt) {
            this.username = username;
            this.expiresAt = expiresAt;
        }
    }

    private final Store store;
    private final Config config;
    private final byte[] secret;
    private final Map<String, RefreshSession> refreshSessions = new ConcurrentHashMap<>();

    public Security(Store store, Config config) {
        this.store = store;
        this.config = config;
        this.secret = config.jwtSecret.getBytes(StandardCharsets.UTF_8);
    }

    public static String hash(String salt, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest((salt + ":" + password).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public Map<String, Object> login(String username, String password) {
        Map<String, Object> row = store.find(Store.USERS, u -> username.equals(u.get("username")))
                .stream().findFirst().orElse(null);
        if (row == null || Json.bool(row.get("disabled"), false)) {
            throw Errors.unauthorized("账号或密码错误");
        }
        String expected = Json.str(row.get("passwordHash"), "");
        String actual = hash(Json.str(row.get("salt"), ""), password);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8))) {
            throw Errors.unauthorized("账号或密码错误");
        }
        User user = new User(username, Json.str(row.get("name"), username), Json.str(row.get("role"), BUSINESS));
        return issue(user, true);
    }

    public Map<String, Object> refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) throw Errors.unauthorized("缺少刷新令牌");
        RefreshSession session = refreshSessions.remove(refreshToken);
        if (session == null || session.expiresAt < Instant.now().getEpochSecond()) {
            throw Errors.unauthorized("刷新令牌无效或已过期，请重新登录");
        }
        Map<String, Object> row = store.get(Store.USERS, session.username);
        if (row == null) throw Errors.unauthorized("账号已不存在");
        User user = new User(session.username, Json.str(row.get("name"), ""), Json.str(row.get("role"), BUSINESS));
        return issue(user, true);
    }

    private Map<String, Object> issue(User user, boolean withRefresh) {
        long now = Instant.now().getEpochSecond();
        long exp = now + config.accessTokenSeconds;
        Map<String, Object> header = Json.obj("alg", "HS256", "typ", "JWT");
        Map<String, Object> payload = Json.obj(
                "sub", user.username, "role", user.role, "name", user.name,
                "iat", now, "exp", exp, "jti", Ids.uuid());
        String unsigned = b64(Json.stringify(header)) + "." + b64(Json.stringify(payload));
        String token = unsigned + "." + hmac(unsigned);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accessToken", token);
        out.put("tokenType", "Bearer");
        out.put("expiresIn", config.accessTokenSeconds);
        out.put("user", user.view());
        if (withRefresh) {
            String refresh = Ids.uuid() + Ids.uuid();
            refreshSessions.put(refresh, new RefreshSession(user.username, now + config.refreshTokenSeconds));
            out.put("refreshToken", refresh);
        }
        return out;
    }

    public User authenticate(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw Errors.unauthorized("请先登录");
        }
        String token = authHeader.substring(7).trim();
        String[] parts = token.split("\\.");
        if (parts.length != 3) throw Errors.unauthorized("令牌格式错误");
        String unsigned = parts[0] + "." + parts[1];
        if (!MessageDigest.isEqual(hmac(unsigned).getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            throw Errors.unauthorized("令牌签名校验失败");
        }
        Map<String, Object> payload;
        try {
            payload = Json.asMap(Json.parse(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw Errors.unauthorized("令牌内容损坏");
        }
        if (Json.lng(payload.get("exp"), 0) < Instant.now().getEpochSecond()) throw Errors.tokenExpired();
        String username = Json.str(payload.get("sub"), "");
        Map<String, Object> row = store.get(Store.USERS, username);
        if (row == null || Json.bool(row.get("disabled"), false)) throw Errors.unauthorized("账号不可用");
        return new User(username, Json.str(row.get("name"), username), Json.str(row.get("role"), BUSINESS));
    }

    /** RBAC：ADMIN 拥有全部权限；其余角色必须命中端点声明的角色。 */
    public void require(HttpCtx ctx, String... roles) {
        if (roles == null || roles.length == 0) return;
        if (ctx.user == null) throw Errors.unauthorized("请先登录");
        if (ADMIN.equals(ctx.user.role)) return;
        for (String r : roles) {
            if (r.equals(ctx.user.role)) return;
        }
        throw Errors.forbidden("当前角色无权执行此操作，需要: " + String.join("/", roles));
    }

    public static String roleLabel(String role) { return ROLE_LABELS.getOrDefault(role, role); }

    private String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }
}
