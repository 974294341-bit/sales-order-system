package com.salesorder.modules;

import com.salesorder.platform.Errors;
import com.salesorder.platform.Ids;
import com.salesorder.platform.Json;
import com.salesorder.platform.Security;
import com.salesorder.platform.Store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 统一审批中心：PC 端与手机端共用同一份待办，审批结果交给流程引擎推进业务状态。 */
public final class ApprovalService {
    /**
     * 优化：审批端（含手机端）权限白名单。审批人只能填写评审数据与意见，
     * 不能借审批接口篡改订单核心字段；修改订单必须走《销售订单变更及通知单》。
     */
    private static final Set<String> ALLOWED_EXTRA = Set.of(
            "comment", "bomNote", "plannedFinishDate", "deliveryReply", "archiveNo",
            "creditOk", "priceOk", "paymentTerms", "capacityNote", "customerAgreedNote",
            "handleResult", "costNote", "amountDeltaNote", "slaNote",
            "qualityNote", "purchaseNote");

    private final Store store;
    private final FlowEngine flow;

    public ApprovalService(Store store, FlowEngine flow) {
        this.store = store;
        this.flow = flow;
    }

    public List<Map<String, Object>> list(String role, String status, String bizId, int limit) {
        List<Map<String, Object>> out = store.find(Store.APPROVALS, t -> {
            if (role != null && !role.isBlank() && !Security.ADMIN.equals(role)
                    && !role.equals(t.get("role"))) return false;
            if (status != null && !status.isBlank() && !"ALL".equals(status)
                    && !status.equals(t.get("status"))) return false;
            if (bizId != null && !bizId.isBlank() && !bizId.equals(t.get("bizId"))) return false;
            return true;
        });
        out.sort(Comparator.comparing((Map<String, Object> t) -> Json.str(t.get("createdAt"), "")).reversed());
        if (limit > 0 && out.size() > limit) return new ArrayList<>(out.subList(0, limit));
        return out;
    }

    public Map<String, Object> get(String id) {
        return store.require(Store.APPROVALS, id, "审批任务");
    }

    public Map<String, Object> stats(String role) {
        List<Map<String, Object>> all = list(role, "ALL", null, 0);
        int pending = 0, approved = 0, rejected = 0, overdue = 0, escalated = 0;
        String today = Ids.today();
        for (Map<String, Object> t : all) {
            String s = Json.str(t.get("status"), "");
            if (FlowEngine.PENDING.equals(s)) {
                pending++;
                if (today.compareTo(Json.str(t.get("dueDate"), "9999-12-31")) > 0) overdue++;
                if (Json.bool(t.get("escalated"), false)) escalated++;
            } else if (FlowEngine.APPROVED.equals(s)) approved++;
            else if (FlowEngine.REJECTED.equals(s)) rejected++;
        }
        return Json.obj("pending", pending, "approved", approved, "rejected", rejected,
                "overdue", overdue, "escalated", escalated, "total", all.size(), "role", role);
    }

    /** 通过或驳回；extra 承载 BOM 说明、完工日期、归档号等流程数据，随审批回写单据。 */
    public Map<String, Object> decide(String id, Security.User user, boolean approve,
                                      String comment, Map<String, Object> extra) {
        return store.tx(() -> {
            Map<String, Object> task = store.require(Store.APPROVALS, id, "审批任务");
            if (!FlowEngine.PENDING.equals(task.get("status"))) {
                throw Errors.conflict("该审批任务已处理，不能重复操作");
            }
            String role = Json.str(task.get("role"), "");
            if (!Security.ADMIN.equals(user.role) && !role.equals(user.role)) {
                throw Errors.forbidden("此任务指派给「" + Security.roleLabel(role) + "」，当前角色无权处理");
            }
            Map<String, Object> payload = new LinkedHashMap<>(Json.asMap(task.get("payload")));
            if (extra != null) {
                for (String key : extra.keySet()) {
                    if (!ALLOWED_EXTRA.contains(key)) {
                        throw Errors.validation("审批接口不允许写入业务字段「" + key
                                + "」；审批人只能填写评审意见，修改订单请走《销售订单变更及通知单》");
                    }
                }
                payload.putAll(extra);
            }
            payload.put("comment", comment);

            Map<String, Object> updated = store.update(Store.APPROVALS, id, Json.obj(
                    "status", approve ? FlowEngine.APPROVED : FlowEngine.REJECTED,
                    "comment", comment,
                    "operator", user.username,
                    "operatorName", user.name,
                    "decidedAt", Ids.now(),
                    "payload", payload));
            flow.onApprovalDone(updated);
            return updated;
        });
    }

    public List<Map<String, Object>> timeline(String bizId) {
        return flow.timeline(bizId);
    }
}
