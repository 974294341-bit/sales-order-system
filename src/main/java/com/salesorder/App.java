package com.salesorder;

import com.salesorder.modules.ApiRoutes;
import com.salesorder.modules.SeedData;
import com.salesorder.platform.Config;
import com.salesorder.platform.Log;
import com.salesorder.platform.Router;
import com.salesorder.platform.Security;
import com.salesorder.platform.Store;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/** 应用入口：装配依赖 → 注册路由 → 启动 → 优雅停机。 */
public final class App {
    private App() {}

    public static void main(String[] args) {
        Config config;
        try {
            config = Config.load();
        } catch (IllegalStateException e) {
            System.err.println("配置校验失败: " + e.getMessage());
            System.exit(1);
            return;
        }

        Store store = new Store(config);
        Security security = new Security(store, config);
        SeedData.seed(store, config);
        Router router = new Router(config, security);
        ApiRoutes.register(router, store, config, security);

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(config.bindAddress, config.port), 0);
            server.createContext("/", router);
            server.setExecutor(Executors.newFixedThreadPool(8));
            server.start();
            Log.info("server_started", "port", config.port, "bind", config.bindAddress,
                    "schemaVersion", store.schemaVersion(), "routes", router.routeSummary().size());
            System.out.println("销售订货系统已启动: http://" + config.bindAddress + ":" + config.port);
            System.out.println("演示账号见 README.md，统一密码 123456");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Log.info("server_stopping", "reason", "shutdown_hook");
                server.stop(2);
                Log.info("server_stopped");
            }));
        } catch (Exception e) {
            Log.error("server_start_failed", "message", String.valueOf(e.getMessage()));
            System.err.println("启动失败: " + e.getMessage());
            System.exit(1);
        }
    }
}
