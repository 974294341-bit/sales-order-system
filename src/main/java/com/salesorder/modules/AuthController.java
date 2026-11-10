package com.salesorder.modules;

import com.salesorder.platform.HttpCtx;
import com.salesorder.platform.Ids;
import com.salesorder.platform.Json;
import com.salesorder.platform.Resp;
import com.salesorder.platform.Security;
import com.salesorder.platform.Store;

/** 控制器：认证、界面元数据、健康检查。不含业务规则。 */
public final class AuthController {
    private final Security security;
    private final MasterDataService master;
    private final Store store;

    public AuthController(Security security, MasterDataService master, Store store) {
        this.security = security;
        this.master = master;
        this.store = store;
    }

    public Object login(HttpCtx ctx) {
        return Resp.ok(security.login(ctx.required("username"), ctx.required("password")));
    }

    public Object refresh(HttpCtx ctx) {
        return Resp.ok(security.refresh(ctx.required("refreshToken")));
    }

    public Object me(HttpCtx ctx) {
        return Resp.ok(ctx.user.view());
    }

    public Object meta(HttpCtx ctx) {
        return Resp.ok(master.meta(ctx.user));
    }

    public Object health(HttpCtx ctx) {
        return Resp.ok(Json.obj("status", "UP", "time", Ids.now()));
    }

    public Object ready(HttpCtx ctx) {
        return Resp.ok(Json.obj("status", "READY", "schemaVersion", store.schemaVersion(),
                "orders", store.count(Store.ORDERS), "approvals", store.count(Store.APPROVALS)));
    }
}
