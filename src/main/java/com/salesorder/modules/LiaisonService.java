package com.salesorder.modules;

import com.salesorder.platform.Errors;
import com.salesorder.platform.Ids;
import com.salesorder.platform.Json;
import com.salesorder.platform.Security;
import com.salesorder.platform.Store;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 5.7：非订单类需求以《联络单》处理，联络单须经业务部负责人审批通过。 */
public final class LiaisonService {
    private final Store store;
    private final FlowEngine flow;

    public LiaisonService(Store store, FlowEngine flow) {
        this.store = store;
        this.flow = flow;
    }

    public List<Map<String, Object>> list(String status) {
        List<Map<String, Object>> out = store.find(Store.LIAISONS,
                l -> status == null || status.isBlank() || status.equals(l.get("status")));
        out.sort(Comparator.comparing((Map<String, Object> l) -> Json.str(l.get("createdAt"), "")).reversed());
        return out;
    }

    public Map<String, Object> get(String id) {
        return store.require(Store.LIAISONS, id, "联络单");
    }

    public Map<String, Object> create(Map<String, Object> input, Security.User user) {
        String title = Json.str(input.get("title"), "");
        if (title.isBlank()) throw Errors.validation("联络单标题不能为空");
        String content = Json.str(input.get("content"), "");
        if (content.isBlank()) throw Errors.validation("联络单需求内容不能为空");

        Map<String, Object> form = Json.obj(
                "liaisonNo", Ids.next("LS"), "title", title, "content", content,
                "department", Json.str(input.get("department"), "业务部"),
                "expectedDate", Json.str(input.get("expectedDate"), ""),
                "status", "PENDING", "createdBy", user.username, "createdByName", user.name);

        return store.tx(() -> {
            Map<String, Object> created = store.insert(Store.LIAISONS, form);
            flow.createTask("LIAISON_APPROVE", "LIAISON", Json.str(created.get("id"), ""),
                    Security.BUSINESS_MANAGER, "业务部负责人审批《联络单》 " + created.get("liaisonNo")
                            + "：" + title, 1, 1, Json.obj("liaisonNo", created.get("liaisonNo")));
            return created;
        });
    }
}
