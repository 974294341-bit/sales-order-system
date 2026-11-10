package com.salesorder.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 全部配置来自环境变量，启动时集中校验、快速失败。 */
public final class Config {
    public final int port;
    public final Path dataDir;
    public final Path webDir;
    public final List<String> corsOrigins;
    public final int accessTokenSeconds;
    public final long refreshTokenSeconds;
    public final String jwtSecret;
    public final boolean seedDemoData;
    public final String bindAddress;
    /** 急插单月度上限，防止无限插单挤压正常订单产能。 */
    public final int rushMonthlyLimit;
    /** 审批超时是否自动升级给部门主管。 */
    public final boolean slaEscalateEnabled;
    /** demo 模式下是否每次启动重置业务演示数据；生产默认关闭。 */
    public final boolean demoReset;

    private Config(int port, Path dataDir, Path webDir, List<String> corsOrigins,
                   int accessTokenSeconds, long refreshTokenSeconds, String jwtSecret,
                   boolean seedDemoData, String bindAddress, int rushMonthlyLimit,
                   boolean slaEscalateEnabled, boolean demoReset) {
        this.port = port;
        this.dataDir = dataDir;
        this.webDir = webDir;
        this.corsOrigins = corsOrigins;
        this.accessTokenSeconds = accessTokenSeconds;
        this.refreshTokenSeconds = refreshTokenSeconds;
        this.jwtSecret = jwtSecret;
        this.seedDemoData = seedDemoData;
        this.bindAddress = bindAddress;
        this.rushMonthlyLimit = rushMonthlyLimit;
        this.slaEscalateEnabled = slaEscalateEnabled;
        this.demoReset = demoReset;
    }

    public static Config load() {
        int port = intEnv("APP_PORT", 8080);
        if (port < 1 || port > 65535) throw new IllegalStateException("APP_PORT 必须在 1..65535 之间");

        int access = intEnv("APP_ACCESS_TOKEN_SECONDS", 1800);
        if (access < 60) throw new IllegalStateException("APP_ACCESS_TOKEN_SECONDS 至少 60 秒");

        long refresh = longEnv("APP_REFRESH_TOKEN_SECONDS", 604800L);
        if (refresh < access) throw new IllegalStateException("刷新令牌有效期必须大于访问令牌");

        String secret = env("APP_JWT_SECRET", "dev-only-change-me-32bytes-min");
        if (secret.length() < 16) throw new IllegalStateException("APP_JWT_SECRET 至少 16 个字符");

        Path dataDir = Path.of(env("APP_DATA_DIR", "data")).toAbsolutePath().normalize();
        Path webDir = Path.of(env("APP_WEB_DIR", "web")).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dataDir);
        } catch (Exception e) {
            throw new IllegalStateException("APP_DATA_DIR 不可写: " + dataDir, e);
        }
        if (!Files.isDirectory(webDir)) {
            throw new IllegalStateException("APP_WEB_DIR 不存在: " + webDir);
        }

        List<String> origins = Arrays.stream(env("APP_CORS_ORIGINS", "http://127.0.0.1:8080,http://localhost:8080").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (origins.isEmpty()) throw new IllegalStateException("APP_CORS_ORIGINS 不能为空");
        for (String o : origins) {
            if ("*".equals(o)) throw new IllegalStateException("CORS 不允许通配符来源");
        }

        boolean seed = boolEnv("APP_SEED_DEMO_DATA", true);
        String bind = env("APP_BIND_ADDRESS", "127.0.0.1");
        int rushLimit = intEnv("APP_RUSH_MONTHLY_LIMIT", 5);
        if (rushLimit < 1) throw new IllegalStateException("APP_RUSH_MONTHLY_LIMIT 至少为 1");
        boolean slaEscalate = boolEnv("APP_SLA_ESCALATE", true);
        boolean demoReset = boolEnv("APP_DEMO_RESET", seed);
        return new Config(port, dataDir, webDir, origins, access, refresh, secret, seed, bind,
                rushLimit, slaEscalate, demoReset);
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static int intEnv(String key, int fallback) {
        try { return Integer.parseInt(env(key, String.valueOf(fallback)).trim()); }
        catch (NumberFormatException e) { throw new IllegalStateException(key + " 必须是整数"); }
    }

    private static long longEnv(String key, long fallback) {
        try { return Long.parseLong(env(key, String.valueOf(fallback)).trim()); }
        catch (NumberFormatException e) { throw new IllegalStateException(key + " 必须是整数"); }
    }

    private static boolean boolEnv(String key, boolean fallback) {
        String v = env(key, String.valueOf(fallback));
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    public Map<String, Object> summary() {
        return Json.obj(
                "port", port,
                "dataDir", dataDir.toString(),
                "webDir", webDir.toString(),
                "corsOrigins", corsOrigins,
                "accessTokenSeconds", accessTokenSeconds,
                "seedDemoData", seedDemoData,
                "rushMonthlyLimit", rushMonthlyLimit,
                "slaEscalateEnabled", slaEscalateEnabled);
    }
}
