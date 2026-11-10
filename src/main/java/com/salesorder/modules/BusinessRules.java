package com.salesorder.modules;

import com.salesorder.platform.Errors;
import com.salesorder.platform.Ids;
import com.salesorder.platform.Json;
import com.salesorder.platform.Store;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 业务规则集中层：所有「交期必须符合《产品生产周期表》」的判断都收敛到这里。
 * 优化点：原流程只在新建订单校验，变更与预测可以绕过；现在新建、变更、延期、预测共用同一规则。
 */
public final class BusinessRules {
    private final Store store;

    public BusinessRules(Store store) { this.store = store; }

    /** 取最新已受控发行版本中的周期天数；未发行时退回产品档案里的默认周期。 */
    public int publishedCycleDays(String productId, Map<String, Object> product) {
        List<Map<String, Object>> published = store.find(Store.CYCLE_TABLES,
                t -> "PUBLISHED".equals(t.get("status")));
        published.sort(Comparator.comparing((Map<String, Object> t) -> Json.str(t.get("publishedAt"), "")).reversed());
        if (!published.isEmpty()) {
            Map<String, Object> rows = Json.asMap(published.get(0).get("rows"));
            if (rows.containsKey(productId)) return Json.integer(rows.get(productId), 0);
        }
        return Json.integer(product.get("cycleDays"), 0);
    }

    /** 一张订单明细里最长的生产周期。 */
    public int maxCycleDays(List<Object> items) {
        int max = 0;
        for (Object o : items) {
            Map<String, Object> item = Json.asMap(o);
            String pid = Json.str(item.get("productId"), "");
            Map<String, Object> product = store.get(Store.PRODUCTS, pid);
            if (product != null) max = Math.max(max, publishedCycleDays(pid, product));
        }
        return max;
    }

    /** 校验交期：距今天的工作日数不得小于最长生产周期。scene 用于错误提示定位到具体环节。 */
    public void assertDeliveryCycle(List<Object> items, String deliveryDate, String scene) {
        if (deliveryDate == null || deliveryDate.isBlank()) return;
        int lead = Ids.workdaysBetween(LocalDate.now(), Ids.parseDate(deliveryDate));
        int max = maxCycleDays(items);
        if (lead < max) {
            throw Errors.validation(scene + "交期不满足《产品生产周期表》（5.2.3）：最长生产周期 "
                    + max + " 个工作日，当前仅 " + lead + " 个工作日");
        }
    }
}
