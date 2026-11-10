package com.salesorder.modules;

import com.salesorder.platform.Errors;
import com.salesorder.platform.Ids;
import com.salesorder.platform.Json;
import com.salesorder.platform.Security;
import com.salesorder.platform.Store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 5.4：订单评审完成后的变更，走《销售订单变更及通知单》→ 计划部重新评审 → ERP 变更更新。 */
public final class ChangeService {
    private final Store store;
    private final FlowEngine flow;

    private final BusinessRules rules;

    public ChangeService(Store store, FlowEngine flow) {
        this.store = store;
        this.flow = flow;
        this.rules = new BusinessRules(store);
    }

    public List<Map<String, Object>> list(String orderId) {
        List<Map<String, Object>> out = store.find(Store.CHANGES,
                c -> orderId == null || orderId.isBlank() || orderId.equals(c.get("orderId")));
        out.sort(Comparator.comparing((Map<String, Object> c) -> Json.str(c.get("createdAt"), "")).reversed());
        return out;
    }

    public Map<String, Object> get(String id) {
        return store.require(Store.CHANGES, id, "变更单");
    }

    /** 变更类型标准字典；旧表单值做兼容映射，避免历史数据/旧前端提交失败。 */
    public static String normalizeType(Object raw) {
        return switch (Json.str(raw, "DELIVERY").toUpperCase()) {
            case "QUANTITY", "QTY" -> "QUANTITY";
            case "SPEC", "PRODUCT", "BOM" -> "SPEC";
            case "PENDING_CONFIRM" -> "DELIVERY";
            default -> "DELIVERY";
        };
    }

    public Map<String, Object> create(Map<String, Object> input, Security.User user) {
        String orderId = Json.str(input.get("orderId"), "");
        if (orderId.isBlank()) throw Errors.validation("缺少必填字段: orderId");
        Map<String, Object> order = store.require(Store.ORDERS, orderId, "销售订单");
        String type = normalizeType(input.get("type"));
        String reason = Json.str(input.get("reason"), "");
        if (reason.isBlank()) throw Errors.validation("变更原因不能为空");
        String newDate = Json.str(input.get("newDeliveryDate"), "");
        if (!newDate.isBlank()) Ids.parseDate(newDate);
        // 优化：变更修改交期与新建订单一样，强制校验《产品生产周期表》，拦截小于标准周期的交期
        rules.assertDeliveryCycle(Json.asList(order.get("items")), newDate, "订单变更");
        // 集中状态机：已发货/已关闭/已取消的订单不允许再发起变更
        StateMachine.assertTransition(Json.str(order.get("status"), ""), "CHANGING");

        double amountDelta = Json.dbl(input.get("amountDelta"), 0);
        List<String> reviewers = new ArrayList<>();
        Map<String, Object> change = Json.obj(
                "changeNo", Ids.next("CHG"), "orderId", orderId,
                "orderNo", order.get("orderNo"), "type", type, "reason", reason,
                "oldDeliveryDate", order.get("deliveryDate"), "newDeliveryDate", newDate,
                "previousStatus", order.get("status"),
                "versionBefore", Json.integer(order.get("version"), 1),
                "amountDelta", amountDelta,
                "details", Json.str(input.get("details"), ""),
                "status", "PENDING", "createdBy", user.username, "createdByName", user.name);

        return store.tx(() -> {
            Map<String, Object> created = store.insert(Store.CHANGES, change);
            String changeId = Json.str(created.get("id"), "");
            String changeNo = Json.str(created.get("changeNo"), "");
            String orderNo = Json.str(order.get("orderNo"), orderId);
            Map<String, Object> payload = Json.obj("changeNo", changeNo, "orderId", orderId,
                    "type", type, "amountDelta", amountDelta);
            // 优化：按变更类型自动分派评审人，全部并行会签；仅交期变更不再重跑工程 BOM 评审
            if ("DELIVERY".equals(type)) {
                reviewers.add("计划部");
                flow.createTask("CHANGE_REVIEW", "ORDER_CHANGE", changeId, Security.PLANNING,
                        "计划部评审交期变更 " + changeNo + "（" + orderNo + "）", 1, 1, payload);
            } else if ("QUANTITY".equals(type)) {
                reviewers.add("计划部");
                reviewers.add("财务部");
                flow.createTask("CHANGE_REVIEW", "ORDER_CHANGE", changeId, Security.PLANNING,
                        "计划部评审数量变更 " + changeNo + " 的产能与物料", 1, 1, payload);
                flow.createTask("CHANGE_FINANCE", "ORDER_CHANGE", changeId, Security.FINANCE,
                        "财务部确认数量变更 " + changeNo + " 的订单金额变化", 1, 1, payload);
            } else {
                reviewers.add("工程部");
                reviewers.add("计划部");
                reviewers.add("采购部");
                reviewers.add("财务部");
                flow.createTask("CHANGE_BOM", "ORDER_CHANGE", changeId, Security.ENGINEERING,
                        "工程部重审产品/BOM 规格变更 " + changeNo, 1, 1, payload);
                flow.createTask("CHANGE_REVIEW", "ORDER_CHANGE", changeId, Security.PLANNING,
                        "计划部评审规格变更 " + changeNo + " 的交期影响", 1, 1, payload);
                flow.createTask("CHANGE_COSIGN_PURCHASE", "ORDER_CHANGE", changeId, Security.PURCHASING,
                        "采购部会签规格变更 " + changeNo + " 的物料齐套", 1, 1, payload);
                flow.createTask("CHANGE_FINANCE", "ORDER_CHANGE", changeId, Security.FINANCE,
                        "财务部确认规格变更 " + changeNo + " 的成本与售价", 1, 1, payload);
            }
            store.update(Store.CHANGES, changeId, Json.obj("reviewers", reviewers));
            store.update(Store.ORDERS, orderId, Json.obj("status", "CHANGING"));
            flow.appendTimeline(orderId, "业务部填写《销售订单变更及通知单》 " + changeNo
                    + "（" + type + "），按类型分派并行会签：" + String.join("、", reviewers));
            return store.get(Store.CHANGES, changeId);
        });
    }
}
