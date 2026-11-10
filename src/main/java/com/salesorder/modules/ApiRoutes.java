package com.salesorder.modules;

import com.salesorder.platform.Config;
import com.salesorder.platform.Router;
import com.salesorder.platform.Security;
import com.salesorder.platform.Store;

/** 依赖装配 + 端点注册表：控制器只做解析与格式化，业务规则都在服务层。 */
public final class ApiRoutes {
    private ApiRoutes() {}

    public static void register(Router router, Store store, Config config, Security security) {
        FlowEngine flow = new FlowEngine(store);
        ApprovalService approvalService = new ApprovalService(store, flow);
        DeliveryService deliveryService = new DeliveryService(store, flow);
        OrderService orderService = new OrderService(store, flow, deliveryService, config);
        ChangeService changeService = new ChangeService(store, flow);
        LiaisonService liaisonService = new LiaisonService(store, flow);
        ForecastService forecastService = new ForecastService(store, flow);
        StatsService statsService = new StatsService(store, flow);
        CycleService cycleService = new CycleService(store, flow);
        MasterDataService master = new MasterDataService(store, config);
        SlaService slaService = new SlaService(store, flow, config);
        SlaController sla = new SlaController(slaService);
        AnalyticsService analyticsService = new AnalyticsService(store);
        AnalyticsController analytics = new AnalyticsController(analyticsService);

        AuthController auth = new AuthController(security, master, store);
        OrderController orders = new OrderController(orderService, changeService, approvalService);
        ApprovalController approvals = new ApprovalController(approvalService, orderService);
        DocumentController docs = new DocumentController(deliveryService, liaisonService,
                forecastService, statsService, cycleService, master);

        String BIZ = Security.BUSINESS;
        String PLAN = Security.PLANNING;
        String ADM = Security.ADMIN;

        // 健康检查与认证
        router.get("/health", auth::health);
        router.get("/ready", auth::ready);
        router.post("/api/auth/login", auth::login);
        router.post("/api/auth/refresh", auth::refresh);
        router.get("/api/auth/me", auth::me);
        router.get("/api/meta", auth::meta);

        // 工作台聚合（分布图 / 进度图数据源）
        router.get("/api/analytics", ctx -> analytics.overview());

        // 5.1 / 5.2 / 5.3 / 5.5 / 5.6 销售订单
        router.post("/api/orders/check-inventory", orders::checkInventory, BIZ, PLAN, ADM);
        router.get("/api/orders", orders::list);
        router.post("/api/orders", orders::create, BIZ, ADM);
        router.get("/api/orders/{id}/timeline", orders::timeline);
        router.post("/api/orders/{id}/submit", orders::submit, BIZ, ADM);
        router.post("/api/orders/{id}/produce", orders::produce, PLAN, ADM);
        router.post("/api/orders/{id}/in-stock", orders::inStock, PLAN, ADM);
        router.post("/api/orders/{id}/delay-notice", orders::delayNotice, PLAN, ADM);
        router.get("/api/orders/{id}", orders::get);

        // 5.4 订单变更
        router.get("/api/changes", orders::changesList);
        router.post("/api/changes", orders::changesCreate, BIZ, ADM);
        router.get("/api/changes/{id}", orders::changesGet);

        // 统一审批中心（PC + 手机端）
        router.get("/api/approvals/stats", approvals::stats);
        router.get("/api/approvals", approvals::list);
        router.get("/api/timeline", approvals::timeline);
        router.post("/api/approvals/{id}/approve", ctx -> { ctx.body.put("approve", true); return approvals.decide(ctx); });
        router.post("/api/approvals/{id}/reject", ctx -> { ctx.body.put("approve", false); return approvals.decide(ctx); });
        router.post("/api/approvals/{id}/decide", approvals::decide);
        router.get("/api/approvals/{id}", approvals::get);

        // 5.1 / 5.6 发货单
        router.get("/api/delivery-notes", docs::dnList);
        router.post("/api/delivery-notes", docs::dnCreate, BIZ, Security.WAREHOUSE, ADM);
        router.get("/api/delivery-notes/{id}", docs::dnGet);

        // 5.7 联络单
        router.get("/api/liaisons", docs::liaisonList);
        router.post("/api/liaisons", docs::liaisonCreate, BIZ, ADM);
        router.get("/api/liaisons/{id}", docs::liaisonGet);

        // 5.8 预测需求计划
        router.get("/api/forecasts", docs::forecastList);
        // 优化：业务输出《预测需求计划》，统一由 PMC 计划员录入/转化，避免多人重复建单
        router.post("/api/forecasts", docs::forecastCreate, PLAN, ADM);
        router.post("/api/forecasts/{id}/submit", docs::forecastSubmit, PLAN, ADM);
        router.get("/api/forecasts/{id}", docs::forecastGet);

        // 5.9 准时交付率
        router.get("/api/reports", docs::reportList);
        router.get("/api/reports/delivery-rate", docs::reportGenerate);
        router.post("/api/reports/delivery-rate", docs::reportGenerate, PLAN, BIZ, ADM);
        router.post("/api/reports/{id}/measures", docs::reportMeasures, PLAN, ADM);
        router.get("/api/reports/{id}", docs::reportGet);

        // 5.10 产品生产周期表
        router.get("/api/cycle-tables", docs::cycleList);
        router.post("/api/cycle-tables", docs::cycleCreate, PLAN, ADM);
        router.get("/api/cycle-tables/{id}", docs::cycleGet);

        // 全节点 SLA：超时清单与主管升级
        router.get("/api/sla/overdue", sla::overdue);
        router.post("/api/sla/scan", sla::scan, PLAN, Security.LEADER, ADM);

        // 基础资料
        router.get("/api/products", docs::products);
        router.post("/api/products", docs::productCreate, PLAN, ADM);
        router.get("/api/customers", docs::customers);
        router.post("/api/customers", docs::customerCreate, BIZ, ADM);
        router.get("/api/inventory-logs", docs::inventoryLogs);

        // 演示数据：demo 模式启动后用真实业务流批量生成（仅当业务表为空或开启重置）
        DemoData.ensure(store, config, security, orderService, changeService, liaisonService,
                forecastService, approvalService, flow);
    }
}
