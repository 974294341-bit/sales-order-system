package com.salesorder.modules;

import com.salesorder.platform.HttpCtx;
import com.salesorder.platform.Json;
import com.salesorder.platform.Resp;
import com.salesorder.platform.Security;

import java.util.LinkedHashMap;
import java.util.Map;

/** 控制器：统一审批中心（PC 端与手机端共用）。 */
public final class ApprovalController {
    private final ApprovalService approvals;
    private final OrderService orders;

    public ApprovalController(ApprovalService approvals, OrderService orders) {
        this.approvals = approvals;
        this.orders = orders;
    }

    public Object list(HttpCtx ctx) {
        String role = ctx.q("role", ctx.user.role);
        if (!Security.ADMIN.equals(ctx.user.role)) role = ctx.user.role; // 普通用户只能看自己的待办
        String status = ctx.q("status", "PENDING");
        String bizId = ctx.q("bizId", "");
        int limit = Json.integer(ctx.q("limit"), 200);
        return Resp.ok(Json.obj("items", approvals.list(role, status, bizId, limit),
                "stats", approvals.stats(role)));
    }

    public Object get(HttpCtx ctx) {
        return Resp.ok(approvals.get(ctx.param("id")));
    }

    public Object stats(HttpCtx ctx) {
        return Resp.ok(approvals.stats(ctx.user.role));
    }

    public Object decide(HttpCtx ctx) {
        boolean approve = ctx.boolField("approve", true);
        String comment = ctx.optional("comment", approve ? "同意" : "不同意");
        Map<String, Object> extra = new LinkedHashMap<>(ctx.mapField("extra"));
        for (String key : new String[]{"bomNote", "plannedFinishDate", "deliveryReply", "archiveNo"}) {
            if (ctx.body.get(key) != null) extra.put(key, ctx.body.get(key));
        }
        return Resp.ok(approvals.decide(ctx.param("id"), ctx.user, approve, comment, extra));
    }

    public Object timeline(HttpCtx ctx) {
        String bizId = ctx.q("bizId", ctx.param("id"));
        Map<String, Object> order = orders.get(bizId);
        return Resp.ok(Json.obj("tasks", approvals.timeline(bizId),
                "events", order.get("timeline"), "order", order));
    }
}
