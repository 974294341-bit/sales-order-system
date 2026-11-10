package com.salesorder.modules;

import com.salesorder.platform.Errors;
import com.salesorder.platform.Ids;
import com.salesorder.platform.Json;
import com.salesorder.platform.Security;
import com.salesorder.platform.Store;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 5.10：《产品生产周期表》每半年更新 → 分管领导审批 → 文控中心受控发行。 */
public final class CycleService {
    private final Store store;
    private final FlowEngine flow;

    public CycleService(Store store, FlowEngine flow) {
        this.store = store;
        this.flow = flow;
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = store.list(Store.CYCLE_TABLES);
        out.sort(Comparator.comparing((Map<String, Object> t) -> Json.str(t.get("createdAt"), "")).reversed());
        return out;
    }

    public Map<String, Object> get(String id) {
        return store.require(Store.CYCLE_TABLES, id, "产品生产周期表");
    }

    public Map<String, Object> createDraft(Map<String, Object> input, Security.User user) {
        String version = Json.str(input.get("version"), "");
        if (version.isBlank()) throw Errors.validation("缺少必填字段: version（如 2026-H2）");
        Object rawRows = input.get("rows");
        if (!(rawRows instanceof Map<?, ?>)) throw Errors.validation("rows 必须是 {产品ID: 周期天数} 对象");
        Map<String, Object> rows = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) rawRows).entrySet()) {
            String pid = String.valueOf(e.getKey());
            if (store.get(Store.PRODUCTS, pid) == null) throw Errors.validation("产品不存在: " + pid);
            int days = Json.integer(e.getValue(), -1);
            if (days <= 0) throw Errors.validation("产品 " + pid + " 的生产周期必须为正整数");
            rows.put(pid, days);
        }

        Map<String, Object> table = Json.obj(
                "version", version, "rows", rows, "remark", Json.str(input.get("remark"), ""),
                "status", "DRAFT", "createdBy", user.username, "createdByName", user.name);

        return store.tx(() -> {
            Map<String, Object> created = store.insert(Store.CYCLE_TABLES, table);
            flow.createTask("CYCLE_APPROVE", "CYCLE_TABLE", Json.str(created.get("id"), ""),
                    Security.LEADER, "分管领导审批《产品生产周期表》 " + version + "（半年更新）",
                    1, 2, Json.obj("version", version));
            return created;
        });
    }
}
