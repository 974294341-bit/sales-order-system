package com.salesorder.modules;

import com.salesorder.platform.Resp;

/** 工作台聚合数据接口。 */
public final class AnalyticsController {
    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    public Object overview() {
        return Resp.ok(analytics.overview());
    }
}
