package com.salesorder.modules;

import com.salesorder.platform.HttpCtx;
import com.salesorder.platform.Json;
import com.salesorder.platform.Resp;

import java.util.List;
import java.util.Map;

/** 控制器：审批超时清单与升级扫描。 */
public final class SlaController {
    private final SlaService sla;

    public SlaController(SlaService sla) { this.sla = sla; }

    public Object overdue(HttpCtx ctx) {
        List<Map<String, Object>> items = sla.overdue();
        return Resp.ok(Json.obj("items", items, "total", items.size()));
    }

    public Object scan(HttpCtx ctx) { return Resp.ok(sla.scan(ctx.user)); }
}
