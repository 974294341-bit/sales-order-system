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

/** 5.8：月度订单交货需求分析 →《预测需求计划》→ 计划部转成预测销售订单（免评审）。 */
public final class ForecastService {
    private final Store store;
    private final FlowEngine flow;

    public ForecastService(Store store, FlowEngine flow) {
        this.store = store;
        this.flow = flow;
    }

    public List<Map<String, Object>> list(String status) {
        List<Map<String, Object>> out = store.find(Store.FORECASTS,
                f -> status == null || status.isBlank() || status.equals(f.get("status")));
        out.sort(Comparator.comparing((Map<String, Object> f) -> Json.str(f.get("createdAt"), "")).reversed());
        return out;
    }

    public Map<String, Object> get(String id) {
        return store.require(Store.FORECASTS, id, "预测需求计划");
    }

    public Map<String, Object> create(Map<String, Object> input, Security.User user) {
        String month = Json.str(input.get("month"), "");
        if (month.isBlank()) throw Errors.validation("缺少必填字段: month（格式 yyyy-MM）");
        List<Object> raw = input.get("items") instanceof List<?> ? Json.asList(input.get("items")) : List.of();
        if (raw.isEmpty()) throw Errors.validation("预测明细不能为空");
        List<Object> items = new ArrayList<>();
        for (Object o : raw) {
            Map<String, Object> item = Json.asMap(o);
            String pid = Json.str(item.get("productId"), "");
            Map<String, Object> product = store.require(Store.PRODUCTS, pid, "产品");
            int qty = Json.integer(item.get("quantity"), 0);
            if (qty <= 0) throw Errors.validation("预测数量必须大于 0");
            items.add(Json.obj("productId", pid, "productName", product.get("name"),
                    "quantity", qty, "price", product.get("price"), "unit", product.get("unit")));
        }

        Map<String, Object> forecast = Json.obj(
                "forecastNo", Ids.next("FC"), "month", month,
                "meetingDate", Json.str(input.get("meetingDate"), Ids.today()),
                "planDate", Json.str(input.get("planDate"), Ids.date(Ids.plusWorkdays(30))),
                "items", items, "remark", Json.str(input.get("remark"), ""),
                "status", "DRAFT", "createdBy", user.username, "createdByName", user.name);
        return store.insert(Store.FORECASTS, forecast);
    }

    /** 提交给计划部：手机端审批通过后由流程引擎自动转成预测销售订单。 */
    public Map<String, Object> submitForConvert(String id, Security.User user) {
        return store.tx(() -> {
            Map<String, Object> forecast = store.require(Store.FORECASTS, id, "预测需求计划");
            if (!"DRAFT".equals(forecast.get("status"))) throw Errors.conflict("该预测计划已提交或已处理");
            store.update(Store.FORECASTS, id, Json.obj("status", "PENDING_CONVERT"));
            Map<String, Object> task = flow.createTask("FORECAST_CONVERT", "FORECAST", id,
                    Security.PLANNING, "计划部将《预测需求计划》 " + forecast.get("forecastNo")
                            + " 在系统转化为《销售订单》", 1, 1,
                    Json.obj("forecastNo", forecast.get("forecastNo"), "month", forecast.get("month")));
            return Json.obj("forecast", store.get(Store.FORECASTS, id), "task", task);
        });
    }
}
