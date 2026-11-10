package com.salesorder.modules;

import com.salesorder.platform.HttpCtx;
import com.salesorder.platform.Json;
import com.salesorder.platform.Resp;

import java.util.List;
import java.util.Map;

/** 控制器：销售订单、评审链、库存预判、生产入库、订单变更。 */
public final class OrderController {
    private final OrderService orders;
    private final ChangeService changes;
    private final ApprovalService approvals;

    public OrderController(OrderService orders, ChangeService changes, ApprovalService approvals) {
        this.orders = orders;
        this.changes = changes;
        this.approvals = approvals;
    }

    public Object list(HttpCtx ctx) {
        List<Map<String, Object>> items = orders.list(ctx.query);
        return Resp.ok(Json.obj("items", items, "total", items.size()));
    }

    public Object create(HttpCtx ctx) {
        return Resp.created(orders.create(ctx.body, ctx.user));
    }

    public Object get(HttpCtx ctx) {
        return Resp.ok(orders.get(ctx.param("id")));
    }

    public Object submit(HttpCtx ctx) {
        return Resp.ok(orders.submit(ctx.param("id"), ctx.user));
    }

    public Object produce(HttpCtx ctx) {
        return Resp.ok(orders.produce(ctx.param("id"), ctx.user));
    }

    public Object inStock(HttpCtx ctx) {
        return Resp.ok(orders.inStock(ctx.param("id"), ctx.user));
    }

    public Object checkInventory(HttpCtx ctx) {
        return Resp.ok(orders.checkInventory(ctx.listField("items")));
    }

    public Object delayNotice(HttpCtx ctx) {
        return Resp.created(orders.createDelayNotice(ctx.param("id"), ctx.body, ctx.user));
    }

    public Object timeline(HttpCtx ctx) {
        String id = ctx.param("id");
        Map<String, Object> order = orders.get(id);
        return Resp.ok(Json.obj("order", order, "events", order.get("timeline"),
                "tasks", approvals.timeline(id)));
    }

    public Object changesList(HttpCtx ctx) {
        List<Map<String, Object>> items = changes.list(ctx.q("orderId"));
        return Resp.ok(Json.obj("items", items, "total", items.size()));
    }

    public Object changesCreate(HttpCtx ctx) {
        return Resp.created(changes.create(ctx.body, ctx.user));
    }

    public Object changesGet(HttpCtx ctx) {
        return Resp.ok(changes.get(ctx.param("id")));
    }
}
