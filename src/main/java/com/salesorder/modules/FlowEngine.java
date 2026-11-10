package com.salesorder.modules;

import com.salesorder.platform.Errors;
import com.salesorder.platform.Ids;
import com.salesorder.platform.Json;
import com.salesorder.platform.Security;
import com.salesorder.platform.Store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程引擎：唯一的审批任务生产者 + 审批完成后的状态推进者。
 * 按流程图实现的业务流程关联集中在这里，PC 端与手机端共用同一套任务。
 */
public final class FlowEngine {
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String CANCELLED = "CANCELLED";

    public static final String DRAFT = "DRAFT";
    public static final String IN_REVIEW = "IN_REVIEW";
    public static final String ORDER_APPROVED = "APPROVED";
    public static final String ORDER_REJECTED = "REJECTED";
    public static final String IN_PRODUCTION = "IN_PRODUCTION";
    public static final String IN_STOCK = "IN_STOCK";
    public static final String SHIPPED = "SHIPPED";
    public static final String STOCK_DIRECT = "STOCK_DIRECT";

    public static final Map<String, String> ORDER_STATUS_LABELS = Map.ofEntries(
            Map.entry(DRAFT, "草稿"),
            Map.entry(IN_REVIEW, "评审中"),
            Map.entry(ORDER_APPROVED, "已审核归档"),
            Map.entry(ORDER_REJECTED, "评审驳回"),
            Map.entry(IN_PRODUCTION, "生产中"),
            Map.entry(IN_STOCK, "已完工入库"),
            Map.entry(SHIPPED, "已发货"),
            Map.entry(STOCK_DIRECT, "库存直发（免评审）"),
            Map.entry("CHANGING", "变更处理中"),
            Map.entry("CLOSED", "已关闭"),
            Map.entry("CANCELLED", "已取消"));

    private final Store store;
    private final BusinessRules rules;

    public FlowEngine(Store store) {
        this.store = store;
        this.rules = new BusinessRules(store);
    }

    /** 创建一条审批任务；dueWorkdays 为工作日时限。 */
    public Map<String, Object> createTask(String type, String bizType, String bizId, String role,
                                          String title, int stage, int dueWorkdays, Map<String, Object> payload) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("type", type);
        task.put("bizType", bizType);
        task.put("bizId", bizId);
        task.put("role", role);
        task.put("roleLabel", Security.roleLabel(role));
        task.put("title", title);
        task.put("status", PENDING);
        task.put("stage", stage);
        task.put("dueDate", Ids.date(Ids.plusWorkdays(dueWorkdays)));
        task.put("payload", payload == null ? new LinkedHashMap<>() : payload);
        task.put("comment", "");
        task.put("createdAt", Ids.now());
        return store.insert(Store.APPROVALS, task);
    }

    /** 5.3：提交订单进入评审链。财务前置 → 并行会签；预测销售免签字评审但保留基础校验。 */
    public List<Map<String, Object>> startOrderReview(Map<String, Object> order) {
        String id = Json.str(order.get("id"), "");
        String status = Json.str(order.get("status"), DRAFT);
        boolean forecast = "FORECAST".equals(Json.str(order.get("salesType"), "NORMAL"));
        boolean nonStandard = Json.bool(order.get("nonStandard"), false);
        int total = nonStandard ? 3 : 2;

        Map<String, Object> patch = new LinkedHashMap<>();
        if (forecast) {
            // 优化：预测订单豁免「签字评审」，但绝不豁免工程基础校验
            assertOrderBasics(Json.asList(order.get("items")), Json.str(order.get("deliveryDate"), ""));
            StateMachine.assertForecastDirect(status);
            patch.put("status", ORDER_APPROVED);
            patch.put("reviewStage", 99);
            patch.put("totalStages", 0);
            patch.put("archived", true);
            patch.put("financeChecked", true);
            store.update(Store.ORDERS, id, patch);
            appendTimeline(id, "预测销售免多部门签字评审；基础校验（样品/试产/BOM/生产周期）已强制通过");
            return List.of();
        }
        // 优化：评审前增加财务前置节点（客户信用额度、订单价格、收款条款）
        StateMachine.assertTransition(status, IN_REVIEW);
        patch.put("status", IN_REVIEW);
        patch.put("reviewStage", 0);
        patch.put("totalStages", total);
        patch.put("archived", false);
        patch.put("financeChecked", false);
        store.update(Store.ORDERS, id, patch);
        appendTimeline(id, "提交流程：先由财务部校验价格/客户信用/收款条款，通过后再并行评审（共 " + total + " 阶段）");
        Map<String, Object> payload = Json.obj("orderNo", order.get("orderNo"),
                "amount", order.get("amount"), "customerName", order.get("customerName"),
                "creditLimit", order.get("creditLimit"));
        List<Map<String, Object>> created = new ArrayList<>();
        created.add(createTask("ORDER_FINANCE", "ORDER", id, Security.FINANCE,
                "财务前置校验 " + Json.str(order.get("orderNo"), id) + "：价格、客户信用额度、收款条款", 0, 1, payload));
        return created;
    }

    private List<Map<String, Object>> tasksForStage(Map<String, Object> order, String orderId,
                                                    int stage, boolean nonStandard) {
        String no = Json.str(order.get("orderNo"), orderId);
        List<Map<String, Object>> created = new ArrayList<>();
        Map<String, Object> payload = Json.obj("orderNo", no, "quantityTotal", order.get("quantityTotal"));
        if (nonStandard) {
            if (stage == 1) {
                created.add(createTask("ORDER_BOM", "ORDER", orderId, Security.ENGINEERING,
                        "工程部评审 " + no + "：确认产品要求并制定订单 BOM 初稿", stage,
                        bomDueWorkdays(), payload));
            } else if (stage == 2) {
                // 优化：BOM 初稿完成后，工程、计划、采购、品质四部门并行会签，不再串行等待
                created.add(createTask("ORDER_COSIGN_ENGINEERING", "ORDER", orderId, Security.ENGINEERING,
                        "非标产品会签 " + no + "：确认 BOM 定稿与工程要求", stage, 1, payload));
                created.add(createTask("ORDER_DELIVERY", "ORDER", orderId, Security.PLANNING,
                        "计划部交期评审 " + no + "：回复具体生产完工日期", stage, 1, payload));
                created.add(createTask("ORDER_COSIGN_PURCHASE", "ORDER", orderId, Security.PURCHASING,
                        "非标产品会签 " + no + "：采购确认物料", stage, 1, payload));
                created.add(createTask("ORDER_COSIGN_QUALITY", "ORDER", orderId, Security.QUALITY,
                        "非标产品会签 " + no + "：品质确认检验要求", stage, 1, payload));
            } else if (stage == 3) {
                created.add(createTask("ORDER_CONFIRM", "ORDER", orderId, Security.BUSINESS,
                        "业务部确认 " + no + " 交期、系统审核并归档纸质评审文档", stage, 1, payload));
            }
        } else {
            // 优化：标准产品工程部 BOM 复核与计划部交期评审并行，不再串行等待
            if (stage == 1) {
                created.add(createTask("ORDER_BOM", "ORDER", orderId, Security.ENGINEERING,
                        "工程部并行复核 " + no + " 的 BOM 与产品要求", stage, 1, payload));
                created.add(createTask("ORDER_DELIVERY", "ORDER", orderId, Security.PLANNING,
                        "计划部并行评审交期 " + no + "：回复完工日期", stage, 1, payload));
            } else if (stage == 2) {
                created.add(createTask("ORDER_CONFIRM", "ORDER", orderId, Security.BUSINESS,
                        "业务部确认 " + no + " 交期、系统审核并归档", stage, 1, payload));
            }
        }
        return created;
    }

    /**
     * 优化：预测/免评审订单共用的工程基础校验。豁免的是「多部门签字」，不是业务规则。
     * 校验样品鉴定、试产状态、BOM 是否存在、交期是否满足《产品生产周期表》。
     */
    public void assertOrderBasics(List<Object> items, String deliveryDate) {
        int lead = deliveryDate.isBlank() ? 0
                : Ids.workdaysBetween(java.time.LocalDate.now(), Ids.parseDate(deliveryDate));
        for (Object o : items) {
            Map<String, Object> item = Json.asMap(o);
            String pid = Json.str(item.get("productId"), "");
            Map<String, Object> product = store.get(Store.PRODUCTS, pid);
            if (product == null) throw Errors.validation("基础校验未通过：产品不存在 " + pid);
            String name = Json.str(product.get("name"), pid);
            if (!Json.bool(product.get("sampleApproved"), false) || !Json.bool(product.get("trialPassed"), false)) {
                throw Errors.validation("基础校验未通过：「" + name + "」样品鉴定或试产未通过（5.2.1）");
            }
            if (!Json.bool(product.get("bomReady"), false)) {
                throw Errors.validation("基础校验未通过：「" + name + "」尚未完成 BOM 制定（5.2.2）");
            }
            int cycle = rules.publishedCycleDays(pid, product);
            if (lead < cycle) {
                throw Errors.validation("基础校验未通过：「" + name + "」交期距今天 " + lead
                        + " 个工作日，小于《产品生产周期表》规定的 " + cycle + " 个工作日（5.2.3）");
            }
        }
    }

    /** 工程部 BOM 时限：从接收评审任务当日开始统计待重做 BOM 的任务数，最多顺延 3 个工作日。 */
    private int bomDueWorkdays() {
        String today = Ids.today();
        long count = store.find(Store.APPROVALS, a -> "ORDER_BOM".equals(a.get("type"))
                && Json.str(a.get("createdAt"), "").startsWith(today)).size() + 1;
        return count <= 10 ? 1 : (int) Math.min(3, 1 + (count - 10));
    }

    /** 审批完成后由 ApprovalService 在事务内调用，负责推进所有业务单据的状态。 */
    public void onApprovalDone(Map<String, Object> task) {
        String bizType = Json.str(task.get("bizType"), "");
        String type = Json.str(task.get("type"), "");
        String bizId = Json.str(task.get("bizId"), "");
        String operator = Json.str(task.get("operatorName"), Json.str(task.get("operator"), ""));
        Map<String, Object> extra = Json.asMap(task.get("payload"));

        if (REJECTED.equals(task.get("status"))) {
            handleReject(bizType, bizId, operator, extra);
            return;
        }
        switch (bizType) {
            case "ORDER" -> advanceOrder(task, extra, operator);
            case "ORDER_RUSH" -> advanceRush(type, bizId, operator, extra);
            case "DELIVERY_NOTE" -> advanceDeliveryNote(type, bizId, operator);
            case "ORDER_CHANGE" -> advanceChange(type, bizId, operator, extra);
            case "LIAISON" -> advanceLiaison(type, bizId, operator, extra);
            case "CYCLE_TABLE" -> advanceCycleTable(type, bizId, operator);
            case "FORECAST" -> advanceForecast(bizId, operator);
            case "DELIVERY_REPORT" -> {
                store.update(Store.DELIVERY_REPORTS, bizId, Json.obj("status", "SUBMITTED",
                        "submittedTo", "PLANNING", "reviewedBy", operator, "submittedAt", Ids.now()));
            }
            case "SLA" -> { /* 超时升级任务只做提醒与告警，不推进业务单据状态 */ }
            default -> throw Errors.internal("未支持的审批业务类型: " + bizType);
        }
    }

    private void handleReject(String bizType, String bizId, String operator, Map<String, Object> extra) {
        switch (bizType) {
            case "ORDER" -> {
                store.update(Store.ORDERS, bizId, Json.obj("status", ORDER_REJECTED, "reviewStage", -1));
                appendTimeline(bizId, "评审被 " + operator + " 驳回：" + Json.str(extra.get("comment"), ""));
                cancelRemaining(bizId, "ORDER");
            }
            case "DELIVERY_NOTE" -> store.update(Store.DELIVERY_NOTES, bizId, Json.obj("status", REJECTED));
            case "ORDER_CHANGE" -> store.update(Store.CHANGES, bizId, Json.obj("status", REJECTED));
            case "ORDER_RUSH" -> {
                store.update(Store.ORDERS, bizId, Json.obj("status", "CANCELLED", "rushApproved", false));
                appendTimeline(bizId, "急插单前置校验被驳回：" + Json.str(extra.get("comment"), "产能不足或客户不同意顺延"));
                cancelRemaining(bizId, "ORDER_RUSH");
            }
            case "LIAISON" -> store.update(Store.LIAISONS, bizId, Json.obj("status", REJECTED));
            case "CYCLE_TABLE" -> store.update(Store.CYCLE_TABLES, bizId, Json.obj("status", REJECTED));
            case "FORECAST" -> store.update(Store.FORECASTS, bizId, Json.obj("status", REJECTED));
            case "DELIVERY_REPORT" -> store.update(Store.DELIVERY_REPORTS, bizId, Json.obj("status", REJECTED));
            default -> { /* 未知类型仅记录 */ }
        }
    }

    private void cancelRemaining(String bizId, String bizType) {
        for (Map<String, Object> t : store.find(Store.APPROVALS, a ->
                bizId.equals(a.get("bizId")) && bizType.equals(a.get("bizType")) && PENDING.equals(a.get("status")))) {
            store.update(Store.APPROVALS, Json.str(t.get("id"), ""), Json.obj("status", CANCELLED));
        }
    }

    private void advanceOrder(Map<String, Object> task, Map<String, Object> extra, String operator) {
        String bizId = Json.str(task.get("bizId"), "");
        String type = Json.str(task.get("type"), "");
        int stage = Json.integer(task.get("stage"), 1);
        Map<String, Object> order = store.require(Store.ORDERS, bizId, "销售订单");

        Map<String, Object> patch = new LinkedHashMap<>();
        if ("ORDER_FINANCE".equals(type)) {
            patch.put("financeChecked", true);
            patch.put("financeBy", operator);
            patch.put("financeAt", Ids.now());
            patch.put("creditOk", Json.bool(extra.get("creditOk"), true));
            patch.put("priceOk", Json.bool(extra.get("priceOk"), true));
            patch.put("paymentTerms", Json.str(extra.get("paymentTerms"), "按合同约定"));
        }
        if ("ORDER_BOM".equals(type) && extra.get("bomNote") != null) {
            patch.put("bomNote", extra.get("bomNote"));
            patch.put("bomReady", true);
        }
        if ("ORDER_DELIVERY".equals(type)) {
            if (extra.get("plannedFinishDate") != null) patch.put("plannedFinishDate", extra.get("plannedFinishDate"));
            patch.put("deliveryReply", Json.str(extra.get("deliveryReply"), "计划部已回复交期"));
        }
        if ("ORDER_CONFIRM".equals(type) && extra.get("archiveNo") != null) patch.put("archiveNo", extra.get("archiveNo"));
        if (!patch.isEmpty()) store.update(Store.ORDERS, bizId, patch);
        appendTimeline(bizId, Security.roleLabel(Json.str(task.get("role"), "")) + " 完成「" + Json.str(task.get("title"), "评审") + "」");

        long pending = store.find(Store.APPROVALS, a -> bizId.equals(a.get("bizId"))
                && "ORDER".equals(a.get("bizType"))
                && Json.integer(a.get("stage"), 0) == stage
                && PENDING.equals(a.get("status"))).size();
        if (pending > 0) return; // 并行会签未全部完成，阶段不推进

        boolean nonStandard = Json.bool(order.get("nonStandard"), false);
        if (stage == 0) {
            store.update(Store.ORDERS, bizId, Json.obj("reviewStage", 1, "status", IN_REVIEW));
            tasksForStage(order, bizId, 1, nonStandard);
            appendTimeline(bizId, "财务前置校验通过，进入第 1 阶段" + (nonStandard ? "（工程部 BOM 初稿）" : "（工程/计划并行）"));
            return;
        }
        int total = Json.integer(order.get("totalStages"), 2);
        if (stage >= total) {
            StateMachine.assertTransition(Json.str(order.get("status"), IN_REVIEW), ORDER_APPROVED);
            store.update(Store.ORDERS, bizId, Json.obj("status", ORDER_APPROVED,
                    "reviewStage", 99, "archived", true, "approvedAt", Ids.now()));
            appendTimeline(bizId, "全部评审完成，系统审核《销售订单》并归档，转生产");
        } else {
            int next = stage + 1;
            store.update(Store.ORDERS, bizId, Json.obj("reviewStage", next, "status", IN_REVIEW));
            tasksForStage(order, bizId, next, nonStandard);
            appendTimeline(bizId, "进入第 " + next + " 阶段并行评审");
        }
    }

    /** 5.5 急插单前置：计划部产能校验 + 业务部确认客户同意原单顺延，两者都通过才允许提交评审。 */
    private void advanceRush(String type, String bizId, String operator, Map<String, Object> extra) {
        Map<String, Object> order = store.get(Store.ORDERS, bizId);
        if (order == null) return;
        Map<String, Object> patch = new LinkedHashMap<>();
        if ("RUSH_CAPACITY".equals(type)) {
            patch.put("capacityApproved", true);
            patch.put("capacityBy", operator);
            patch.put("capacityNote", Json.str(extra.get("capacityNote"), "产能可支持"));
        } else if ("RUSH_CONFIRM".equals(type)) {
            patch.put("customerAgreed", true);
            patch.put("customerAgreedBy", operator);
            patch.put("customerAgreedNote", Json.str(extra.get("customerAgreedNote"), "客户同意原订单交期顺延"));
        }
        if (!patch.isEmpty()) store.update(Store.ORDERS, bizId, patch);
        boolean capacity = Json.bool(store.get(Store.ORDERS, bizId).get("capacityApproved"), false);
        boolean agreed = Json.bool(store.get(Store.ORDERS, bizId).get("customerAgreed"), false);
        if (capacity && agreed) {
            Map<String, Object> current = store.get(Store.ORDERS, bizId);
            store.update(Store.ORDERS, bizId, Json.obj("rushApproved", true, "priority", "RUSH"));
            appendTimeline(bizId, "急插单前置校验全部通过（产能可行 + 客户同意顺延），可提交完整评审流程");
            // 优化：客户同意顺延之后才真正执行原订单调配，并留书面《销售订单变更及通知单》
            String relatedId = Json.str(current.get("relatedOrderId"), "");
            if (!relatedId.isBlank()) {
                Map<String, Object> related = store.get(Store.ORDERS, relatedId);
                if (related != null) {
                    String postponed = Json.str(current.get("postponedDate"), "");
                    if (postponed.isBlank()) {
                        postponed = Ids.date(Ids.plusWorkdays(
                                Ids.parseDate(Json.str(related.get("deliveryDate"), Ids.today())), 7));
                    }
                    store.update(Store.ORDERS, relatedId, Json.obj("deliveryDate", postponed));
                    store.insert(Store.CHANGES, Json.obj(
                            "changeNo", Ids.next("CHG"), "orderId", relatedId, "type", "RUSH_NOTICE",
                            "reason", "急插单调配，原订单交期顺延（客户已书面同意）",
                            "oldDeliveryDate", related.get("deliveryDate"), "newDeliveryDate", postponed,
                            "details", "书面通知：急插单 " + Json.str(current.get("orderNo"), bizId) + " 优先排产",
                            "status", "NOTIFIED", "createdBy", operator));
                    appendTimeline(relatedId, "因急插单 " + Json.str(current.get("orderNo"), "")
                            + "（客户已同意），交期顺延至 " + postponed + "，已发出书面变更通知单");
                }
            }
        }
    }

    /** 5.7 联络单闭环：审批 → 计划部处理 → 业务部确认 → 归档，任一环节驳回即终止。 */
    private void advanceLiaison(String type, String bizId, String operator, Map<String, Object> extra) {
        Map<String, Object> form = store.require(Store.LIAISONS, bizId, "联络单");
        String no = Json.str(form.get("liaisonNo"), bizId);
        if ("LIAISON_APPROVE".equals(type)) {
            store.update(Store.LIAISONS, bizId, Json.obj("status", "WAIT_PLANNING",
                    "approvedBy", operator, "approvedAt", Ids.now()));
            createTask("LIAISON_HANDLE", "LIAISON", bizId, Security.PLANNING,
                    "计划部处理《联络单》 " + no + " 并反馈处理结果", 2, 1, Json.obj("liaisonNo", no));
        } else if ("LIAISON_HANDLE".equals(type)) {
            store.update(Store.LIAISONS, bizId, Json.obj("status", "WAIT_CONFIRM",
                    "handledBy", operator, "handleResult", Json.str(extra.get("handleResult"), "计划部已安排处理"),
                    "handledAt", Ids.now()));
            createTask("LIAISON_CONFIRM", "LIAISON", bizId, Security.BUSINESS,
                    "业务部确认《联络单》 " + no + " 处理结果并结案", 3, 1, Json.obj("liaisonNo", no));
        } else if ("LIAISON_CONFIRM".equals(type)) {
            store.update(Store.LIAISONS, bizId, Json.obj("status", "CLOSED",
                    "closedBy", operator, "closedAt", Ids.now(), "archived", true));
        }
    }

    private void advanceDeliveryNote(String type, String bizId, String operator) {
        Map<String, Object> dn = store.require(Store.DELIVERY_NOTES, bizId, "发货单");
        if ("DELIVERY_FINANCE".equals(type)) {
            store.update(Store.DELIVERY_NOTES, bizId, Json.obj("status", "FINANCE_APPROVED",
                    "financeBy", operator, "financeAt", Ids.now()));
            createTask("DELIVERY_WAREHOUSE", "DELIVERY_NOTE", bizId, Security.WAREHOUSE,
                    "仓库按《发货单》 " + Json.str(dn.get("dnNo"), bizId) + " 办理出库发货", 2, 1,
                    Json.obj("dnNo", dn.get("dnNo")));
            return;
        }
        if ("DELIVERY_WAREHOUSE".equals(type)) {
            String shipDate = Ids.today();
            String contractDate = Json.str(dn.get("contractDeliveryDate"), shipDate);
            boolean onTime = shipDate.compareTo(contractDate.isEmpty() ? shipDate : contractDate) <= 0;
            for (Object o : Json.asList(dn.get("items"))) {
                Map<String, Object> item = Json.asMap(o);
                String pid = Json.str(item.get("productId"), "");
                Map<String, Object> product = store.get(Store.PRODUCTS, pid);
                if (product != null) {
                    int left = Math.max(0, Json.integer(product.get("stock"), 0) - Json.integer(item.get("quantity"), 0));
                    store.update(Store.PRODUCTS, pid, Json.obj("stock", left));
                }
                store.insert(Store.INVENTORY_LOGS, Json.obj(
                        "dnNo", dn.get("dnNo"), "productId", pid,
                        "productName", item.get("productName"), "quantity", item.get("quantity"),
                        "direction", "OUT", "operator", operator, "at", Ids.now()));
            }
            store.update(Store.DELIVERY_NOTES, bizId, Json.obj("status", SHIPPED, "warehouseBy", operator,
                    "shipDate", shipDate, "onTime", onTime));
            String orderId = Json.str(dn.get("orderId"), "");
            if (!orderId.isBlank()) {
                Map<String, Object> order = store.get(Store.ORDERS, orderId);
                if (order != null) {
                    StateMachine.assertTransition(Json.str(order.get("status"), ""), SHIPPED);
                    store.update(Store.ORDERS, orderId, Json.obj("status", SHIPPED,
                            "shippedAt", Ids.now(), "onTime", onTime,
                            "onTimeBasis", "以变更确认后的最新约定交期 " + contractDate + " 为基准"));
                    appendTimeline(orderId, "仓库出库发货，发货单 " + Json.str(dn.get("dnNo"), "")
                            + "；准时判定基准为最新约定交期 " + contractDate);
                }
            }
        }
    }

    private void advanceChange(String type, String bizId, String operator, Map<String, Object> extra) {
        Map<String, Object> change = store.require(Store.CHANGES, bizId, "变更单");
        String orderId = Json.str(change.get("orderId"), "");
        if ("DELAY_NOTICE".equals(type)) {
            store.update(Store.CHANGES, bizId, Json.obj("status", "COMMUNICATED",
                    "communicatedBy", operator, "communicatedAt", Ids.now()));
            if (store.get(Store.ORDERS, orderId) != null) {
                appendTimeline(orderId, "计划部提前发出《交期延期通知单》，业务部已与客户沟通");
            }
            return;
        }
        // 按变更类型分派的多个评审人并行会签，全部通过后才执行 ERP 变更动作
        long pending = store.find(Store.APPROVALS, a -> bizId.equals(a.get("bizId"))
                && "ORDER_CHANGE".equals(a.get("bizType")) && PENDING.equals(a.get("status"))).size();
        if (pending > 0) return;

        Map<String, Object> order = store.get(Store.ORDERS, orderId);
        if (order != null) {
            String target = Json.str(change.get("previousStatus"), ORDER_APPROVED);
            StateMachine.assertTransition(Json.str(order.get("status"), "CHANGING"), target);
            int version = Json.integer(order.get("version"), 1);
            List<Object> history = new ArrayList<>(Json.asList(order.get("changeHistory")));
            Map<String, Object> before = Json.obj("version", version,
                    "deliveryDate", order.get("deliveryDate"), "amount", order.get("amount"),
                    "items", order.get("items"));
            Map<String, Object> patch = new LinkedHashMap<>();
            String newDate = Json.str(change.get("newDeliveryDate"), "");
            if (!newDate.isBlank()) patch.put("deliveryDate", newDate);
            if (change.get("details") != null) patch.put("changeDetails", change.get("details"));
            double delta = Json.dbl(change.get("amountDelta"), 0);
            if (delta != 0) patch.put("amount", Json.dbl(order.get("amount"), 0) + delta);
            patch.put("status", target);
            patch.put("version", version + 1);
            Map<String, Object> after = Json.obj("version", version + 1,
                    "deliveryDate", patch.getOrDefault("deliveryDate", order.get("deliveryDate")),
                    "amount", patch.getOrDefault("amount", order.get("amount")),
                    "items", order.get("items"));
            history.add(Json.obj("version", version + 1, "changeNo", change.get("changeNo"),
                    "type", change.get("type"), "before", before, "after", after,
                    "at", Ids.now(), "by", operator));
            patch.put("changeHistory", history);
            store.update(Store.ORDERS, orderId, patch);
            appendTimeline(orderId, "变更单 " + Json.str(change.get("changeNo"), "")
                    + " 全部会签完成，已在系统执行变更动作；订单版本 v" + version + " → v" + (version + 1));
        }
        store.update(Store.CHANGES, bizId, Json.obj("status", "APPLIED", "reviewedBy", operator,
                "appliedAt", Ids.now(), "versionApplied", Json.str(change.get("changeNo"), "")));
    }

    private void advanceCycleTable(String type, String bizId, String operator) {
        Map<String, Object> table = store.require(Store.CYCLE_TABLES, bizId, "产品生产周期表");
        if ("CYCLE_APPROVE".equals(type)) {
            store.update(Store.CYCLE_TABLES, bizId, Json.obj("status", "APPROVED",
                    "approvedBy", operator, "approvedAt", Ids.now()));
            createTask("CYCLE_PUBLISH", "CYCLE_TABLE", bizId, Security.DOC_CONTROL,
                    "文控中心受控发行《产品生产周期表》 " + Json.str(table.get("version"), ""), 2, 1,
                    Json.obj("version", table.get("version")));
        } else if ("CYCLE_PUBLISH".equals(type)) {
            Map<String, Object> rows = Json.asMap(table.get("rows"));
            for (Map.Entry<String, Object> e : rows.entrySet()) {
                if (store.get(Store.PRODUCTS, e.getKey()) != null) {
                    store.update(Store.PRODUCTS, e.getKey(), Json.obj("cycleDays", e.getValue()));
                }
            }
            store.update(Store.CYCLE_TABLES, bizId, Json.obj("status", "PUBLISHED",
                    "publishedBy", operator, "publishedAt", Ids.now()));
        }
    }

    private void advanceForecast(String bizId, String operator) {
        Map<String, Object> forecast = store.require(Store.FORECASTS, bizId, "预测需求计划");
        List<Object> items = Json.asList(forecast.get("items"));
        int total = 0;
        double amount = 0;
        String planDate = Json.str(forecast.get("planDate"), "");
        // 优化：预测订单豁免签字评审，但基础业务校验与正常订单一致，系统强制拦截非法预测订单
        assertOrderBasics(items, planDate);
        for (Object o : items) {
            Map<String, Object> item = Json.asMap(o);
            total += Json.integer(item.get("quantity"), 0);
            amount += Json.dbl(item.get("price"), 0) * Json.integer(item.get("quantity"), 0);
        }
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderNo", Ids.next("FSO"));
        order.put("customerId", "FORECAST");
        order.put("customerName", "预测需求计划");
        order.put("source", "FORECAST");
        order.put("salesType", "FORECAST");
        order.put("rush", false);
        order.put("nonStandard", false);
        order.put("status", ORDER_APPROVED);
        order.put("reviewStage", 99);
        order.put("totalStages", 0);
        order.put("archived", true);
        order.put("items", items);
        order.put("quantityTotal", total);
        order.put("amount", amount);
        order.put("deliveryDate", planDate);
        order.put("forecastId", bizId);
        order.put("createdBy", operator);
        order.put("enteredBy", operator); // 优化：预测订单统一由 PMC 计划员录入，避免多人重复建单
        order.put("financeChecked", true);
        order.put("rushApproved", true);
        order.put("stockShortcut", false);
        order.put("priority", "NORMAL");
        order.put("version", 1);
        order.put("changeHistory", Json.arr());
        order.put("remark", "由《预测需求计划》转化，销售类型为预测销售，免签字评审但基础校验已通过");
        order.put("timeline", Json.arr(Json.obj("at", Ids.now(),
                "text", "PMC 计划员统一录入预测销售订单：基础校验通过，免多部门签字评审")));
        Map<String, Object> created = store.insert(Store.ORDERS, order);
        store.update(Store.FORECASTS, bizId, Json.obj("status", "CONVERTED",
                "convertedOrderId", created.get("id"), "convertedBy", operator, "convertedAt", Ids.now()));
    }

    public void appendTimeline(String orderId, String text) {
        Map<String, Object> order = store.get(Store.ORDERS, orderId);
        if (order == null) return;
        List<Object> timeline = new ArrayList<>(Json.asList(order.get("timeline")));
        timeline.add(Json.obj("at", Ids.now(), "text", text));
        store.update(Store.ORDERS, orderId, Json.obj("timeline", timeline));
    }

    public List<Map<String, Object>> timeline(String bizId) {
        List<Map<String, Object>> tasks = store.find(Store.APPROVALS, a -> bizId.equals(a.get("bizId")));
        tasks.sort((a, b) -> Json.str(a.get("createdAt"), "").compareTo(Json.str(b.get("createdAt"), "")));
        return tasks;
    }
}
