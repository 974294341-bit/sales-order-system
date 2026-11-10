package com.salesorder.modules;

import com.salesorder.platform.Errors;
import com.salesorder.platform.Ids;
import com.salesorder.platform.Json;
import com.salesorder.platform.Security;
import com.salesorder.platform.Store;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 5.9：每月 5 号前统计上月订单准时交付率，提交计划部分析并制定改善措施。 */
public final class StatsService {
    private final Store store;
    private final FlowEngine flow;

    public StatsService(Store store, FlowEngine flow) {
        this.store = store;
        this.flow = flow;
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = store.list(Store.DELIVERY_REPORTS);
        out.sort(Comparator.comparing((Map<String, Object> r) -> Json.str(r.get("month"), "")).reversed());
        return out;
    }

    public Map<String, Object> get(String id) {
        return store.require(Store.DELIVERY_REPORTS, id, "准时交付率报告");
    }

    /** 幂等：同一个月份重复调用只返回已有报告，不重复生成审批任务。 */
    public Map<String, Object> report(String month, Security.User user) {
        if (month == null || month.isBlank()) {
            month = LocalDate.now().minusMonths(1).toString().substring(0, 7);
        }
        final String target = month;
        List<Map<String, Object>> existing = store.find(Store.DELIVERY_REPORTS, r -> target.equals(r.get("month")));
        if (!existing.isEmpty()) return existing.get(0);

        List<Map<String, Object>> shipped = store.find(Store.DELIVERY_NOTES,
                d -> FlowEngine.SHIPPED.equals(d.get("status"))
                        && Json.str(d.get("shipDate"), "").startsWith(target));
        int total = shipped.size();
        int onTimeCount = 0;
        List<Object> lateOrders = new ArrayList<>();
        // 优化：口径统一 —— 以「变更确认后的最新约定交期」为考核基准，并按订单类型分组统计
        Map<String, int[]> groups = new java.util.LinkedHashMap<>();
        groups.put("NORMAL", new int[2]);
        groups.put("RUSH", new int[2]);
        groups.put("FORECAST", new int[2]);
        for (Map<String, Object> d : shipped) {
            Map<String, Object> order = null;
            String orderId = Json.str(d.get("orderId"), "");
            if (!orderId.isBlank()) order = store.get(Store.ORDERS, orderId);
            String basisDate = Json.str(d.get("contractDeliveryDate"), "");
            if (order != null && !Json.str(order.get("deliveryDate"), "").isBlank()) {
                basisDate = Json.str(order.get("deliveryDate"), ""); // 变更后的最新约定交期
            }
            String shipDate = Json.str(d.get("shipDate"), "");
            boolean onTime = basisDate.isBlank() || shipDate.compareTo(basisDate) <= 0;
            String group = "NORMAL";
            if (order != null) {
                if (Json.bool(order.get("rush"), false)) group = "RUSH";
                else if ("FORECAST".equals(Json.str(order.get("salesType"), "NORMAL"))) group = "FORECAST";
            }
            int[] bucket = groups.get(group);
            bucket[0]++;
            if (onTime) { onTimeCount++; bucket[1]++; }
            else lateOrders.add(Json.obj("dnNo", d.get("dnNo"), "customerName", d.get("customerName"),
                    "shipDate", shipDate, "contractDeliveryDate", basisDate, "group", group));
        }
        List<Object> byGroup = new ArrayList<>();
        Map<String, String> groupLabels = Map.of("NORMAL", "正常订单", "RUSH", "急插单", "FORECAST", "预测订单");
        for (Map.Entry<String, int[]> e : groups.entrySet()) {
            int[] v = e.getValue();
            double r = v[0] == 0 ? 100.0 : Math.round(v[1] * 10000.0 / v[0]) / 100.0;
            byGroup.add(Json.obj("group", e.getKey(), "label", groupLabels.get(e.getKey()),
                    "total", v[0], "onTimeCount", v[1], "rate", r));
        }
        double rate = total == 0 ? 100.0 : Math.round(onTimeCount * 10000.0 / total) / 100.0;
        int day = LocalDate.now().getDayOfMonth();
        String basis = "以变更确认后的最新约定交期为考核基准（非订单原始交期）；正常/急插/预测订单分开统计";
        Map<String, Object> report = Json.obj(
                "month", target, "total", total, "onTimeCount", onTimeCount, "lateCount", total - onTimeCount,
                "rate", rate, "lateOrders", lateOrders, "byGroup", byGroup, "basis", basis,
                "generatedAt", Ids.now(), "generatedBy", user.username,
                "beforeDeadline", day <= 5, "deadlineNote", "规定每月 5 号前完成统计并提交计划部",
                "improveMeasures", "", "status", "DRAFT");

        return store.tx(() -> {
            Map<String, Object> created = store.insert(Store.DELIVERY_REPORTS, report);
            flow.createTask("REPORT_REVIEW", "DELIVERY_REPORT", Json.str(created.get("id"), ""),
                    Security.PLANNING, "计划部分析 " + target + " 月准时交付率 " + rate
                            + "% 并提出改善措施", 1, 1, Json.obj("month", target, "rate", rate));
            return created;
        });
    }

    public Map<String, Object> updateMeasures(String id, String measures, Security.User user) {
        if (measures == null || measures.isBlank()) throw Errors.validation("改善措施不能为空");
        return store.update(Store.DELIVERY_REPORTS, id,
                Json.obj("improveMeasures", measures, "measureBy", user.username, "measureAt", Ids.now()));
    }
}
