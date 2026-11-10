package com.salesorder.modules;

import com.salesorder.platform.Config;
import com.salesorder.platform.Ids;
import com.salesorder.platform.Json;
import com.salesorder.platform.Security;
import com.salesorder.platform.Store;

import java.util.List;
import java.util.Map;

/**
 * 优化：所有审批节点统一超时约束与升级机制。
 * 原流程只约束工程部 BOM 时限，计划部/业务部/财务/采购/品质超时无人处理、单据搁置无告警；
 * 这里按任务的 dueDate 扫描，逾期自动标记并升级给部门主管，且保证幂等不重复生成告警任务。
 */
public final class SlaService {
    private final Store store;
    private final FlowEngine flow;
    private final Config config;

    public SlaService(Store store, FlowEngine flow, Config config) {
        this.store = store;
        this.flow = flow;
        this.config = config;
    }

    public List<Map<String, Object>> overdue() {
        String today = Ids.today();
        List<Map<String, Object>> out = store.find(Store.APPROVALS, t ->
                FlowEngine.PENDING.equals(t.get("status"))
                        && today.compareTo(Json.str(t.get("dueDate"), "9999-12-31")) > 0);
        out.sort((a, b) -> Json.str(a.get("dueDate"), "").compareTo(Json.str(b.get("dueDate"), "")));
        return out;
    }

    /** 扫描逾期任务并升级；已升级的任务不会重复生成告警。 */
    public Map<String, Object> scan(Security.User user) {
        if (!config.slaEscalateEnabled) {
            return Json.obj("enabled", false, "scanned", 0, "escalated", 0,
                    "message", "超时升级已由 APP_SLA_ESCALATE=false 关闭");
        }
        return store.tx(() -> {
            List<Map<String, Object>> overdue = overdue();
            int created = 0;
            for (Map<String, Object> task : overdue) {
                String id = Json.str(task.get("id"), "");
                if (Json.bool(task.get("escalated"), false)) continue;
                String sourceRole = Json.str(task.get("role"), "");
                String targetRole = escalateTarget(sourceRole);
                store.update(Store.APPROVALS, id, Json.obj(
                        "escalated", true, "escalatedAt", Ids.now(),
                        "escalateTo", targetRole, "escalateToLabel", Security.roleLabel(targetRole)));
                boolean exists = !store.find(Store.APPROVALS, a -> "SLA".equals(a.get("bizType"))
                        && id.equals(a.get("bizId")) && FlowEngine.PENDING.equals(a.get("status"))).isEmpty();
                if (!exists) {
                    flow.createTask("SLA_ESCALATION", "SLA", id, targetRole,
                            "超时升级：「" + Security.roleLabel(sourceRole) + "」的待办「"
                                    + Json.str(task.get("title"), id) + "」已逾期，请主管催办或转派",
                            0, 1, Json.obj("sourceTaskId", id, "sourceRole", sourceRole,
                                    "sourceDueDate", task.get("dueDate")));
                    created++;
                }
            }
            return Json.obj("enabled", true, "scanned", overdue.size(), "escalated", created,
                    "by", user.username, "at", Ids.now(),
                    "message", "已扫描 " + overdue.size() + " 条逾期待办，新增 " + created + " 条主管升级告警");
        });
    }

    /** 业务部升级给业务部负责人，其余部门升级给分管领导（含人员离职/单据搁置场景）。 */
    private String escalateTarget(String role) {
        return Security.BUSINESS.equals(role) ? Security.BUSINESS_MANAGER : Security.LEADER;
    }
}
