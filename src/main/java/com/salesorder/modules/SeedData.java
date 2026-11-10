package com.salesorder.modules;

import com.salesorder.platform.Config;
import com.salesorder.platform.Json;
import com.salesorder.platform.Security;
import com.salesorder.platform.Store;

import java.util.List;
import java.util.Map;

/** 演示数据初始化：只在对应表为空时写入，绝不覆盖用户已有数据。 */
public final class SeedData {
    private SeedData() {}

    private static final String DEMO_PASSWORD = "123456";

    public static void seed(Store store, Config config) {
        if (!config.seedDemoData) return;
        if (store.count(Store.USERS) == 0) seedUsers(store);
        if (store.count(Store.CUSTOMERS) == 0) seedCustomers(store);
        if (store.count(Store.PRODUCTS) == 0) seedProducts(store);
        if (store.count(Store.CYCLE_TABLES) == 0) seedCycleTable(store);
        if (store.count(Store.DELIVERY_REPORTS) == 0) seedReports(store);
    }

    private static void seedUsers(Store store) {
        List<String[]> users = List.of(
                new String[]{"admin", "系统管理员", Security.ADMIN},
                new String[]{"business", "业务专员", Security.BUSINESS},
                new String[]{"manager", "业务负责人", Security.BUSINESS_MANAGER},
                new String[]{"engineer", "工程评审员", Security.ENGINEERING},
                new String[]{"planner", "计划主管", Security.PLANNING},
                new String[]{"finance", "财务审核员", Security.FINANCE},
                new String[]{"warehouse", "仓库管理员", Security.WAREHOUSE},
                new String[]{"purchase", "采购专员", Security.PURCHASING},
                new String[]{"quality", "品质专员", Security.QUALITY},
                new String[]{"leader", "分管领导", Security.LEADER},
                new String[]{"docctl", "文控中心", Security.DOC_CONTROL});
        for (String[] u : users) {
            String salt = "sales-order-demo-" + u[0];
            store.insert(Store.USERS, Json.obj(
                    "id", u[0], "username", u[0], "name", u[1], "role", u[2],
                    "salt", salt, "passwordHash", Security.hash(salt, DEMO_PASSWORD), "disabled", false));
        }
    }

    private static void seedCustomers(Store store) {
        // 优化：客户档案带信用额度与收款条款，供财务前置节点校验
        store.insert(Store.CUSTOMERS, Json.obj("id", "C001", "code", "C001", "name", "华东贸易有限公司",
                "type", "DOMESTIC", "fixed", true, "contact", "采购部",
                "creditLimit", 500000, "paymentTerms", "月结 30 天"));
        store.insert(Store.CUSTOMERS, Json.obj("id", "C002", "code", "C002", "name", "Global Tech Ltd.",
                "type", "INTERNATIONAL", "fixed", false, "contact", "Overseas Dept.",
                "creditLimit", 300000, "paymentTerms", "预付 30%，发货前结清"));
        store.insert(Store.CUSTOMERS, Json.obj("id", "C003", "code", "C003", "name", "华南制造有限公司",
                "type", "DOMESTIC", "fixed", false, "contact", "物控部",
                "creditLimit", 200000, "paymentTerms", "款到发货"));
    }

    private static void seedProducts(Store store) {
        store.insert(Store.PRODUCTS, Json.obj("id", "P001", "code", "P001", "name", "标准控制器 A1",
                "standard", true, "sampleApproved", true, "trialPassed", true, "bomReady", true,
                "cycleDays", 7, "stock", 120, "unit", "PCS", "price", 320));
        store.insert(Store.PRODUCTS, Json.obj("id", "P002", "code", "P002", "name", "标准控制器 B2",
                "standard", true, "sampleApproved", true, "trialPassed", true, "bomReady", true,
                "cycleDays", 10, "stock", 0, "unit", "PCS", "price", 480));
        store.insert(Store.PRODUCTS, Json.obj("id", "P003", "code", "P003", "name", "非标定制机柜 C3",
                "standard", false, "sampleApproved", true, "trialPassed", true, "bomReady", false,
                "cycleDays", 20, "stock", 0, "unit", "PCS", "price", 2600));
        store.insert(Store.PRODUCTS, Json.obj("id", "P004", "code", "P004", "name", "新产品 D4（未鉴定）",
                "standard", false, "sampleApproved", false, "trialPassed", false, "bomReady", false,
                "cycleDays", 15, "stock", 0, "unit", "PCS", "price", 880));
    }

    private static void seedCycleTable(Store store) {
        Map<String, Object> rows = Json.obj(
                "P001", 7, "P002", 10, "P003", 20, "P004", 15);
        store.insert(Store.CYCLE_TABLES, Json.obj(
                "id", "CT-2026H1", "version", "2026-H1", "status", "PUBLISHED",
                "rows", rows, "approvedBy", "leader", "publishedBy", "docctl",
                "publishedAt", "2026-01-05T09:00:00", "remark", "初始受控发行版本"));
    }

    /** 近 6 个月《准时交付率》历史：让趋势图在空数据时也有内容（仅 DELIVERY_REPORTS 为空时写入）。 */
    private static void seedReports(Store store) {
        java.time.YearMonth now = java.time.YearMonth.now();
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM");
        // 月份回退 6..1（不含当月，演示“上月及以前”的历史趋势）
        int[] totals = { 42, 38, 51, 47, 55, 49 };
        int[] onTime = { 39, 35, 47, 46, 53, 48 };
        for (int i = 6; i >= 1; i--) {
            java.time.YearMonth ym = now.minusMonths(i);
            String month = ym.format(fmt);
            int total = totals[6 - i];
            int ot = onTime[6 - i];
            double rate = Math.round(ot * 10000.0 / total) / 100.0;
            store.insert(Store.DELIVERY_REPORTS, Json.obj(
                    "id", "R-" + month, "month", month, "total", total, "onTimeCount", ot,
                    "lateCount", total - ot, "rate", rate, "basis", "历史演示数据，以最新约定交期为基准",
                    "generatedAt", ym.atDay(5).atStartOfDay().toString(),
                    "generatedBy", "planner", "beforeDeadline", true, "status", "APPROVED"));
        }
    }
}
