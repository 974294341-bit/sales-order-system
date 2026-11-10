package com.salesorder.modules;

import com.salesorder.platform.Config;
import com.salesorder.platform.FlowEngine;
import com.salesorder.platform.Json;
import com.salesorder.platform.Log;
import com.salesorder.platform.Security;
import com.salesorder.platform.Store;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 演示数据生成（仅 demo 模式）：用真实业务流批量造一组饱满且自洽的演示数据，
 * 覆盖订单全生命周期与各角色待办，方便演示 / 录屏。生产模式（APP_SEED_DEMO_DATA=false）不生效。
 */
public final class DemoData {
    private DemoData() {}

    private static final Map<String, Security.User> U = Map.ofEntries(
            Map.entry(Security.ADMIN, new Security.User("admin", "系统管理员", Security.ADMIN)),
            Map.entry(Security.BUSINESS, new Security.User("business", "业务专员", Security.BUSINESS)),
            Map.entry(Security.BUSINESS_MANAGER, new Security.User("manager", "业务负责人", Security.BUSINESS_MANAGER)),
            Map.entry(Security.ENGINEERING, new Security.User("engineer", "工程评审员", Security.ENGINEERING)),
            Map.entry(Security.PLANNING, new Security.User("planner", "计划主管", Security.PLANNING)),
            Map.entry(Security.FINANCE, new Security.User("finance", "财务审核员", Security.FINANCE)),
            Map.entry(Security.WAREHOUSE, new Security.User("warehouse", "仓库管理员", Security.WAREHOUSE)),
            Map.entry(Security.PURCHASING, new Security.User("purchase", "采购专员", Security.PURCHASING)),
            Map.entry(Security.QUALITY, new Security.User("quality", "品质专员", Security.QUALITY)),
            Map.entry(Security.LEADER, new Security.User("leader", "分管领导", Security.LEADER)),
            Map.entry(Security.DOC_CONTROL, new Security.User("docctl", "文控中心", Security.DOC_CONTROL))
    );

    public static void ensure(Store store, Config config, Security security,
                              OrderService orderService, ChangeService changeService,
                              LiaisonService liaisonService, ForecastService forecastService,
                              ApprovalService approvalService, FlowEngine flow) {
        if (!config.seedDemoData) return;                       // 仅 demo 模式
        if (config.demoReset && store.count(Store.ORDERS) > 0) clearBusiness(store);
        if (store.count(Store.ORDERS) > 0) return;             // 已有演示数据则不重复生成
        try {
            generate(store, orderService, changeService, liaisonService, forecastService, approvalService);
            Log.info("demo_data_generate", "result", "ok");
        } catch (Exception e) {
            Log.error("demo_data_generate_failed", "message", String.valueOf(e.getMessage()));
        }
    }

    private static void clearBusiness(Store store) {
        for (String t : List.of(Store.ORDERS, Store.APPROVALS, Store.CHANGES,
                Store.DELIVERY_NOTES, Store.INVENTORY_LOGS, Store.LIAISONS, Store.FORECASTS)) {
            for (Map<String, Object> r : new ArrayList<>(store.list(t))) {
                store.delete(t, Json.str(r.get("id"), ""));
            }
        }
        Log.info("demo_data_reset", "tables", "orders/approvals/changes/delivery_notes/inventory_logs/liaisons/forecasts");
    }

    private static void generate(Store store, OrderService orderService, ChangeService changeService,
                                 LiaisonService liaisonService, ForecastService forecastService,
                                 ApprovalService approvalService) {
        Log.info("demo_data_generate", "start", "real business flow");

        // O1 标准品库存充足 → 库存短路直发 → 已发货（含财务 + 仓库审批）
        Map<String, Object> o1 = order(orderService, "C001", "2026-12-15", List.of(it("P001", 20)), false, false, null);
        Map<String, Object> sub1 = orderService.submit(id(o1), U.get(Security.BUSINESS));
        String dnId = Json.str(Json.asMap(sub1.get("deliveryNote")).get("id"), "");
        approveAll(store, approvalService, dnId, "DELIVERY_NOTE");

        // O2 非标品 → 评审中：推进到「四部门会签」阶段后保留，展示多角色待办
        Map<String, Object> o2 = order(orderService, "C002", "2026-12-20", List.of(it("P003", 5)), false, true, null);
        orderService.submit(id(o2), U.get(Security.BUSINESS));
        decideType(store, approvalService, id(o2), "ORDER", "ORDER_FINANCE");   // 财务前置通过 → 阶段1
        decideType(store, approvalService, id(o2), "ORDER", "ORDER_BOM");       // 工程 BOM 通过 → 阶段2 会签（保留）

        // O3 标准品 → 评审通过归档 → 发起数量变更（CHANGING，保留计划 / 财务会签待办）
        Map<String, Object> o3 = order(orderService, "C001", "2026-12-20", List.of(it("P002", 30)), false, false, null);
        orderService.submit(id(o3), U.get(Security.BUSINESS));
        approveAll(store, approvalService, id(o3), "ORDER");
        changeService.create(Json.obj("orderId", id(o3), "type", "QUANTITY",
                "reason", "客户临时追加采购数量", "amountDelta", 4800.0, "details", "数量 +10"),
                U.get(Security.BUSINESS));

        // O4 标准品 → 评审通过 → 生产中
        Map<String, Object> o4 = order(orderService, "C001", "2026-12-25", List.of(it("P002", 10)), false, false, null);
        orderService.submit(id(o4), U.get(Security.BUSINESS));
        approveAll(store, approvalService, id(o4), "ORDER");
        orderService.produce(id(o4), U.get(Security.PLANNING));

        // O5 标准品 → 评审通过 → 完工入库
        Map<String, Object> o5 = order(orderService, "C001", "2026-12-28", List.of(it("P002", 15)), false, false, null);
        orderService.submit(id(o5), U.get(Security.BUSINESS));
        approveAll(store, approvalService, id(o5), "ORDER");
        orderService.produce(id(o5), U.get(Security.PLANNING));
        orderService.inStock(id(o5), U.get(Security.PLANNING));

        // O7 标准品 → 评审中 → 计划部驳回 → 评审驳回
        Map<String, Object> o7 = order(orderService, "C003", "2026-12-18", List.of(it("P001", 40)), false, false, null);
        orderService.submit(id(o7), U.get(Security.BUSINESS));
        decideType(store, approvalService, id(o7), "ORDER", "ORDER_FINANCE");   // 财务前置通过 → 阶段1 并行
        decideRejectType(store, approvalService, id(o7), "ORDER", "ORDER_DELIVERY"); // 计划部驳回 → 订单驳回

        // O8 草稿（仅建单，不提交）
        order(orderService, "C001", "2026-12-30", List.of(it("P002", 5)), false, false, null);

        // O9 急插单 → 产能 / 客户同意前置 → 评审通过归档
        Map<String, Object> o9 = order(orderService, "C001", "2026-12-15", List.of(it("P002", 10)), true, false, null);
        approveAll(store, approvalService, id(o9), "ORDER_RUSH");               // 计划产能 + 业务客户同意
        orderService.submit(id(o9), U.get(Security.BUSINESS));
        approveAll(store, approvalService, id(o9), "ORDER");

        // O10 预测需求计划 → 计划部转化 → 预测销售订单（已审核归档）
        Map<String, Object> f = forecastService.create(Json.obj("month", "2026-12",
                "items", List.of(Json.obj("productId", "P001", "quantity", 60))), U.get(Security.PLANNING));
        forecastService.submitForConvert(id(f), U.get(Security.PLANNING));
        approveAll(store, approvalService, id(f), "FORECAST");

        // L1 联络单（保留业务部负责人审批待办）
        liaisonService.create(Json.obj("title", "样品包装确认", "content", "请计划部协调包装打样并反馈", "department", "业务部"),
                U.get(Security.BUSINESS));

        // L2 联络单 → 完整闭环（审批 → 处理 → 确认 → 归档）
        Map<String, Object> l2 = liaisonService.create(Json.obj("title", "产线排期协调", "content", "请计划部协调产线排期", "department", "业务部"),
                U.get(Security.BUSINESS));
        approveAll(store, approvalService, id(l2), "LIAISON");
    }

    // ---- helpers ----
    private static Map<String, Object> order(OrderService svc, String customerId, String deliveryDate,
                                            List<Object> items, boolean rush, boolean engConfirm, String related) {
        Map<String, Object> input = Json.obj("customerId", customerId, "deliveryDate", deliveryDate,
                "items", items, "rush", rush, "engineeringConfirmed", engConfirm,
                "relatedOrderId", related == null ? "" : related);
        return svc.create(input, U.get(Security.BUSINESS));
    }

    private static Map<String, Object> it(String pid, int qty) {
        return Json.obj("productId", pid, "quantity", qty);
    }

    private static String id(Map<String, Object> m) {
        return Json.str(m.get("id"), "");
    }

    private static void approveAll(Store store, ApprovalService svc, String bizId, String bizType) {
        int guard = 0;
        while (guard++ < 80) {
            List<Map<String, Object>> pendings = store.find(Store.APPROVALS, a ->
                    bizId.equals(a.get("bizId")) && bizType.equals(a.get("bizType"))
                            && FlowEngine.PENDING.equals(a.get("status")));
            if (pendings.isEmpty()) break;
            for (Map<String, Object> t : pendings) svc.decide(Json.str(t.get("id"), ""),
                    user(t), true, "演示数据：自动通过", null);
        }
    }

    private static void decideType(Store store, ApprovalService svc, String bizId, String bizType, String type) {
        for (Map<String, Object> t : store.find(Store.APPROVALS, a ->
                bizId.equals(a.get("bizId")) && bizType.equals(a.get("bizType"))
                        && type.equals(a.get("type")) && FlowEngine.PENDING.equals(a.get("status")))) {
            svc.decide(Json.str(t.get("id"), ""), user(t), true, "演示数据：自动通过", null);
        }
    }

    private static void decideRejectType(Store store, ApprovalService svc, String bizId, String bizType, String type) {
        for (Map<String, Object> t : store.find(Store.APPROVALS, a ->
                bizId.equals(a.get("bizId")) && bizType.equals(a.get("bizType"))
                        && type.equals(a.get("type")) && FlowEngine.PENDING.equals(a.get("status")))) {
            svc.decide(Json.str(t.get("id"), ""), user(t), false, "演示数据：客户信用 / 产能不满足，予以驳回", null);
        }
    }

    private static Security.User user(Map<String, Object> task) {
        Security.User u = U.get(Json.str(task.get("role"), ""));
        return u == null ? U.get(Security.ADMIN) : u;
    }
}
