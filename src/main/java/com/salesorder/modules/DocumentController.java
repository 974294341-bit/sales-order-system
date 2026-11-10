package com.salesorder.modules;

import com.salesorder.platform.HttpCtx;
import com.salesorder.platform.Json;
import com.salesorder.platform.Resp;

import java.util.List;
import java.util.Map;

/** 控制器：发货单、联络单、预测计划、交付率统计、生产周期表、基础资料。 */
public final class DocumentController {
    private final DeliveryService delivery;
    private final LiaisonService liaisons;
    private final ForecastService forecasts;
    private final StatsService stats;
    private final CycleService cycles;
    private final MasterDataService master;

    public DocumentController(DeliveryService delivery, LiaisonService liaisons, ForecastService forecasts,
                              StatsService stats, CycleService cycles, MasterDataService master) {
        this.delivery = delivery;
        this.liaisons = liaisons;
        this.forecasts = forecasts;
        this.stats = stats;
        this.cycles = cycles;
        this.master = master;
    }

    public Object dnList(HttpCtx ctx) { return paged(delivery.list(ctx.q("status"))); }
    public Object dnCreate(HttpCtx ctx) { return Resp.created(delivery.create(ctx.body, ctx.user)); }
    public Object dnGet(HttpCtx ctx) { return Resp.ok(delivery.get(ctx.param("id"))); }

    public Object liaisonList(HttpCtx ctx) { return paged(liaisons.list(ctx.q("status"))); }
    public Object liaisonCreate(HttpCtx ctx) { return Resp.created(liaisons.create(ctx.body, ctx.user)); }
    public Object liaisonGet(HttpCtx ctx) { return Resp.ok(liaisons.get(ctx.param("id"))); }

    public Object forecastList(HttpCtx ctx) { return paged(forecasts.list(ctx.q("status"))); }
    public Object forecastCreate(HttpCtx ctx) { return Resp.created(forecasts.create(ctx.body, ctx.user)); }
    public Object forecastGet(HttpCtx ctx) { return Resp.ok(forecasts.get(ctx.param("id"))); }
    public Object forecastSubmit(HttpCtx ctx) { return Resp.ok(forecasts.submitForConvert(ctx.param("id"), ctx.user)); }

    public Object reportList(HttpCtx ctx) { return paged(stats.list()); }
    public Object reportGenerate(HttpCtx ctx) { return Resp.ok(stats.report(ctx.q("month"), ctx.user)); }
    public Object reportGet(HttpCtx ctx) { return Resp.ok(stats.get(ctx.param("id"))); }
    public Object reportMeasures(HttpCtx ctx) {
        return Resp.ok(stats.updateMeasures(ctx.param("id"), ctx.required("measures"), ctx.user));
    }

    public Object cycleList(HttpCtx ctx) { return paged(cycles.list()); }
    public Object cycleCreate(HttpCtx ctx) { return Resp.created(cycles.createDraft(ctx.body, ctx.user)); }
    public Object cycleGet(HttpCtx ctx) { return Resp.ok(cycles.get(ctx.param("id"))); }

    public Object products(HttpCtx ctx) { return paged(master.products()); }
    public Object productCreate(HttpCtx ctx) { return Resp.created(master.createProduct(ctx.body)); }
    public Object customers(HttpCtx ctx) { return paged(master.customers()); }
    public Object customerCreate(HttpCtx ctx) { return Resp.created(master.createCustomer(ctx.body)); }
    public Object inventoryLogs(HttpCtx ctx) { return paged(master.inventoryLogs()); }

    private static Object paged(List<Map<String, Object>> items) {
        return Resp.ok(Json.obj("items", items, "total", items.size()));
    }
}
