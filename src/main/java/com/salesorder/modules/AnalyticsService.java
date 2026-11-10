package com.salesorder.modules;

import com.salesorder.platform.Json;
import com.salesorder.platform.Security;
import com.salesorder.platform.Store;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作台数据聚合：为前端分布图 / 进度图提供单一数据源。
 * 所有维度均基于既有单据实时计算，不新增持久化状态。
 */
public final class AnalyticsService {
    private final Store store;

    public AnalyticsService(Store store) {
        this.store = store;
    }

    /** 订单状态分布（用于环形图）。 */
    public List<Object> statusDistribution() {
        List<Map<String, Object>> orders = store.list(Store.ORDERS);
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (Map<String, Object> o : orders) {
            String s = Json.str(o.get("status"), "DRAFT");
            counter.merge(s, 1, Integer::sum);
        }
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counter.entrySet()) {
            out.add(Json.obj("status", e.getKey(),
                    "label", Security.roleLabel(e.getKey()), "count", e.getValue()));
        }
        return out;
    }

    /** 销售类型分布：标准 / 预测 / 急插（用于堆叠条形或环形）。 */
    public List<Object> typeDistribution() {
        List<Map<String, Object>> orders = store.list(Store.ORDERS);
        int normal = 0, forecast = 0, rush = 0;
        for (Map<String, Object> o : orders) {
            boolean isRush = Json.bool(o.get("rush"), false);
            boolean isForecast = "FORECAST".equals(Json.str(o.get("salesType"), "NORMAL"));
            if (isRush) rush++;
            else if (isForecast) forecast++;
            else normal++;
        }
        List<Object> out = new ArrayList<>();
        out.add(Json.obj("key", "NORMAL", "label", "标准/常规订单", "count", normal));
        out.add(Json.obj("key", "FORECAST", "label", "预测销售订单", "count", forecast));
        out.add(Json.obj("key", "RUSH", "label", "急插单", "count", rush));
        return out;
    }

    /** 当前岗位待办分布（用于横向条形图）。 */
    public List<Object> roleDistribution() {
        List<Map<String, Object>> pending = store.find(Store.APPROVALS,
                a -> "PENDING".equals(a.get("status")));
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (Map<String, Object> a : pending) {
            String role = Json.str(a.get("role"), "—");
            counter.merge(role, 1, Integer::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counter.entrySet()) {
            out.add(Json.obj("role", e.getKey(),
                    "label", Security.roleLabel(e.getKey()), "count", e.getValue()));
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> Json.integer(m.get("count"), 0)).reversed());
        return new ArrayList<Object>(out);
    }

    /** 近 6 个月交付趋势（用于面积折线图）。优先取自每月《准时交付率》报告。 */
    public List<Object> monthlyTrend() {
        List<Object> out = new ArrayList<>();
        List<Map<String, Object>> reports = store.list(Store.DELIVERY_REPORTS);
        Map<String, Map<String, Object>> byMonth = new LinkedHashMap<>();
        for (Map<String, Object> r : reports) byMonth.put(Json.str(r.get("month"), ""), r);
        YearMonth now = YearMonth.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM");
        for (int i = 5; i >= 0; i--) {
            YearMonth ym = now.minusMonths(i);
            String key = ym.format(fmt);
            Map<String, Object> r = byMonth.get(key);
            int total = r == null ? 0 : Json.integer(r.get("total"), 0);
            int onTime = r == null ? 0 : Json.integer(r.get("onTimeCount"), 0);
            double rate = r == null ? 100.0 : Json.dbl(r.get("rate"), 100.0);
            out.add(Json.obj("month", key, "total", total, "onTimeCount", onTime, "rate", rate));
        }
        return out;
    }

    /** 汇总卡片与全部图表数据。 */
    public Map<String, Object> overview() {
        int totalOrders = store.list(Store.ORDERS).size();
        List<Map<String, Object>> notes = store.list(Store.DELIVERY_NOTES);
        long shipped = notes.stream().filter(n -> "SHIPPED".equals(n.get("status"))).count();
        List<Map<String, Object>> pending = store.find(Store.APPROVALS,
                a -> "PENDING".equals(a.get("status")));
        long overdue = pending.stream().filter(a ->
                Json.str(a.get("dueDate"), "9999-12-31").compareTo(
                        java.time.LocalDate.now().toString()) < 0).count();
        List<Map<String, Object>> reports = store.list(Store.DELIVERY_REPORTS);
        reports.sort(Comparator.comparing((Map<String, Object> r) -> Json.str(r.get("month"), "")).reversed());
        double onTimeRate = reports.isEmpty() ? 100.0 : Json.dbl(reports.get(0).get("rate"), 100.0);

        return Json.obj(
                "summary", Json.obj(
                        "totalOrders", totalOrders, "shipped", shipped,
                        "pendingApprovals", pending.size(), "overdueApprovals", overdue,
                        "onTimeRate", onTimeRate,
                        "reportMonth", reports.isEmpty() ? "" : Json.str(reports.get(0).get("month"), "")),
                "statusDistribution", statusDistribution(),
                "typeDistribution", typeDistribution(),
                "roleDistribution", roleDistribution(),
                "monthlyTrend", monthlyTrend());
    }
}
