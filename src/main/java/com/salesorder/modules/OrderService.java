package com.salesorder.modules;

import com.salesorder.platform.Config;
import com.salesorder.platform.Errors;
import com.salesorder.platform.Ids;
import com.salesorder.platform.Json;
import com.salesorder.platform.Security;
import com.salesorder.platform.Store;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 5.1 / 5.2 / 5.3 / 5.5 / 5.6：接单与库存判断、订单内容校验、评审提交、急插单、生产入库。
 * 服务层不依赖 HTTP 类型，控制器只负责把请求体转成 Map 传入。
 */
public final class OrderService {
    private final Store store;
    private final FlowEngine flow;
    private final DeliveryService delivery;
    private final Config config;
    private final BusinessRules rules;

    public OrderService(Store store, FlowEngine flow, DeliveryService delivery, Config config) {
        this.store = store;
        this.flow = flow;
        this.delivery = delivery;
        this.config = config;
        this.rules = new BusinessRules(store);
    }

    public List<Map<String, Object>> list(Map<String, String> filters) {
        String status = filters.getOrDefault("status", "");
        String keyword = filters.getOrDefault("q", "").toLowerCase();
        String source = filters.getOrDefault("source", "");
        String rush = filters.getOrDefault("rush", "");
        List<Map<String, Object>> out = store.find(Store.ORDERS, o -> {
            if (!status.isBlank() && !status.equals(o.get("status"))) return false;
            if (!source.isBlank() && !source.equals(o.get("source"))) return false;
            if ("true".equals(rush) && !Json.bool(o.get("rush"), false)) return false;
            if (!keyword.isBlank()) {
                String hay = (Json.str(o.get("orderNo"), "") + " " + Json.str(o.get("customerName"), "")
                        + " " + Json.str(o.get("remark"), "")).toLowerCase();
                if (!hay.contains(keyword)) return false;
            }
            return true;
        });
        out.sort(Comparator.comparing((Map<String, Object> o) -> Json.str(o.get("createdAt"), "")).reversed());
        return out;
    }

    public Map<String, Object> get(String id) {
        return store.require(Store.ORDERS, id, "销售订单");
    }

    /** 5.1：接单预判——库存齐备可直接开《发货单》，否则必须走销售订单评审流程。 */
    public Map<String, Object> checkInventory(List<Object> rawItems) {
        List<Object> details = new ArrayList<>();
        boolean allAvailable = !rawItems.isEmpty();
        for (Object o : rawItems) {
            Map<String, Object> item = Json.asMap(o);
            String pid = Json.str(item.get("productId"), "");
            int qty = Json.integer(item.get("quantity"), 0);
            Map<String, Object> product = store.get(Store.PRODUCTS, pid);
            if (product == null) throw Errors.validation("产品不存在: " + pid);
            int stock = Json.integer(product.get("stock"), 0);
            boolean enough = qty > 0 && stock >= qty;
            if (!enough) allAvailable = false;
            details.add(Json.obj("productId", pid, "productName", product.get("name"),
                    "requested", qty, "stock", stock, "enough", enough));
        }
        return Json.obj("allAvailable", allAvailable,
                "advice", allAvailable ? "库存齐备：按 5.1 直接开具《发货单》，经财务部审核后仓库出货"
                        : "库存不足：按 5.1 / 5.2 在系统生成《销售订单》并进入评审流程",
                "nextAction", allAvailable ? "DELIVERY_NOTE" : "SALES_ORDER",
                "items", details);
    }

    /** 创建销售订单，落地 5.2 全部校验与 5.5 急插单规则。 */
    public Map<String, Object> create(Map<String, Object> input, Security.User user) {
        String customerId = required(input, "customerId");
        Map<String, Object> customer = store.require(Store.CUSTOMERS, customerId, "客户");
        String deliveryDateText = required(input, "deliveryDate");
        LocalDate deliveryDate = Ids.parseDate(deliveryDateText);

        List<Object> rawItems = input.get("items") instanceof List<?> ? Json.asList(input.get("items")) : List.of();
        if (rawItems.isEmpty()) throw Errors.validation("订单明细不能为空");

        List<Object> items = new ArrayList<>();
        int quantityTotal = 0;
        double amount = 0;
        boolean nonStandard = false;
        boolean needsNewBom = false;
        int maxCycleDays = 0;
        for (Object o : rawItems) {
            Map<String, Object> raw = Json.asMap(o);
            String pid = required(raw, "productId");
            Map<String, Object> product = store.require(Store.PRODUCTS, pid, "产品");
            int qty = Json.integer(raw.get("quantity"), 0);
            if (qty <= 0) throw Errors.validation("产品 " + pid + " 数量必须大于 0");

            // 5.2.1 样品鉴定会未召开或试产未通过的新产品不允许下订单
            if (!Json.bool(product.get("sampleApproved"), false) || !Json.bool(product.get("trialPassed"), false)) {
                throw Errors.validation("5.2.1 「" + Json.str(product.get("name"), pid)
                        + "」样品鉴定会未召开或试产未通过，不允许下订单");
            }
            // 5.2.2 BOM 未制定需提前与工程部确认
            if (!Json.bool(product.get("bomReady"), false) && !Json.bool(input.get("engineeringConfirmed"), false)) {
                throw Errors.validation("5.2.2 「" + Json.str(product.get("name"), pid)
                        + "」尚未完成 BOM 制定，下单前需工程部确认");
            }
            boolean standard = Json.bool(product.get("standard"), true);
            if (!standard) nonStandard = true;
            if (!Json.bool(product.get("bomReady"), false)) needsNewBom = true;
            maxCycleDays = Math.max(maxCycleDays, rules.publishedCycleDays(pid, product));

            double price = Json.dbl(raw.get("price"), Json.dbl(product.get("price"), 0));
            quantityTotal += qty;
            amount += price * qty;
            items.add(Json.obj("productId", pid, "productName", product.get("name"),
                    "quantity", qty, "price", price, "unit", product.get("unit"),
                    "specialRequirement", Json.str(raw.get("specialRequirement"), "")));
        }

        // 5.2.3 交期须满足《产品生产周期表》规定
        int leadWorkdays = Ids.workdaysBetween(LocalDate.now(), deliveryDate);
        if (leadWorkdays < maxCycleDays) {
            throw Errors.validation("5.2.3 交期不满足《产品生产周期表》：最长生产周期 " + maxCycleDays
                    + " 个工作日，当前仅 " + leadWorkdays + " 个工作日");
        }
        // 5.2.4 附页内容需详细全面并作确认
        String attachmentNote = Json.str(input.get("attachmentNote"), "");
        if (!attachmentNote.isBlank() && !Json.bool(input.get("attachmentConfirmed"), false)) {
            throw Errors.validation("5.2.4 订单附页内容需确认后才能下单");
        }
        // 5.2.5 不能确认之需求需初步规定确认日期
        String pendingConfirmItem = Json.str(input.get("pendingConfirmItem"), "");
        String pendingConfirmDate = Json.str(input.get("pendingConfirmDate"), "");
        if (!pendingConfirmItem.isBlank() && pendingConfirmDate.isBlank()) {
            throw Errors.validation("5.2.5 存在待确认需求，必须初步规定确认日期");
        }

        boolean rush = Json.bool(input.get("rush"), false);
        if (rush) {
            if (leadWorkdays < 7) {
                throw Errors.validation("5.5 急插单需至少提前 7 个工作日下达，当前仅 " + leadWorkdays + " 个工作日");
            }
            // 优化：设置月度上限，防止无限插单挤压正常订单产能
            String month = LocalDate.now().toString().substring(0, 7);
            long monthCount = store.find(Store.ORDERS, o -> Json.bool(o.get("rush"), false)
                    && Json.str(o.get("createdAt"), "").startsWith(month)
                    && !"CANCELLED".equals(o.get("status"))).size();
            if (monthCount >= config.rushMonthlyLimit) {
                throw Errors.validation("5.5 本月急插单已达上限 " + config.rushMonthlyLimit
                        + " 单（已用 " + monthCount + " 单），请改走正常排产");
            }
        }

        String source = Json.str(input.get("source"), "");
        if (source.isBlank()) source = "INTERNATIONAL".equals(customer.get("type")) ? "INTERNATIONAL" : "DOMESTIC";
        String salesType = Json.str(input.get("salesType"), "NORMAL");
        String relatedOrderId = Json.str(input.get("relatedOrderId"), "");
        String remark = Json.str(input.get("remark"), "");
        if (rush && !remark.contains("急插单")) remark = "急插单（系统标注）。" + remark;
        // 5.1.2 国内固定客户且订单数量 > 200PCS 由市场部业务员直接下达
        boolean directOrder = "DOMESTIC".equals(source) && Json.bool(customer.get("fixed"), false) && quantityTotal > 200;

        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderNo", Ids.next(salesType.equals("FORECAST") ? "FSO" : "SO"));
        order.put("customerId", customerId);
        order.put("customerName", customer.get("name"));
        order.put("source", source);
        order.put("salesType", salesType);
        order.put("rush", rush);
        order.put("nonStandard", nonStandard);
        order.put("containsNewProduct", needsNewBom);
        order.put("status", FlowEngine.DRAFT);
        order.put("reviewStage", 0);
        order.put("totalStages", nonStandard ? 3 : 2);
        order.put("archived", false);
        // 优化：集中状态机所需的版本历史、财务前置、急插单前置与库存短路标记
        order.put("version", 1);
        order.put("changeHistory", new ArrayList<>());
        order.put("financeChecked", false);
        order.put("rushApproved", !rush);
        order.put("capacityApproved", false);
        order.put("customerAgreed", false);
        order.put("stockShortcut", false);
        order.put("priority", rush ? "RUSH" : "NORMAL");
        order.put("creditLimit", Json.dbl(customer.get("creditLimit"), 0));
        order.put("paymentTerms", Json.str(customer.get("paymentTerms"), "按合同约定"));
        order.put("items", items);
        order.put("quantityTotal", quantityTotal);
        order.put("amount", amount);
        order.put("deliveryDate", deliveryDateText);
        order.put("plannedFinishDate", "");
        order.put("bomNote", "");
        order.put("deliveryReply", "");
        order.put("attachmentNote", attachmentNote);
        order.put("attachmentConfirmed", !attachmentNote.isBlank());
        order.put("pendingConfirmItem", pendingConfirmItem);
        order.put("pendingConfirmDate", pendingConfirmDate);
        order.put("relatedOrderId", relatedOrderId);
        order.put("directOrder", directOrder);
        order.put("remark", remark);
        order.put("createdBy", user.username);
        order.put("createdByName", user.name);
        order.put("timeline", Json.arr(Json.obj("at", Ids.now(), "text", "业务员创建《销售订单》草稿")));

        return store.tx(() -> {
            Map<String, Object> created = store.insert(Store.ORDERS, order);
            String orderId = Json.str(created.get("id"), "");
            String orderNo = Json.str(created.get("orderNo"), "");
            if (rush) {
                // 优化：先做产能校验 + 客户同意顺延的并行前置，两者通过才允许进入完整评审；
                // 客户不同意则订单直接取消，原订单交期不会被擅自改动。
                String postponed = "";
                if (!relatedOrderId.isBlank()) {
                    Map<String, Object> related = store.get(Store.ORDERS, relatedOrderId);
                    if (related != null) {
                        postponed = Ids.date(Ids.plusWorkdays(
                                Ids.parseDate(Json.str(related.get("deliveryDate"), Ids.today())), 7));
                    }
                }
                store.update(Store.ORDERS, orderId, Json.obj("postponedDate", postponed));
                Map<String, Object> rushPayload = Json.obj("orderNo", orderNo,
                        "relatedOrderId", relatedOrderId, "deliveryDate", deliveryDateText,
                        "quantityTotal", created.get("quantityTotal"));
                flow.createTask("RUSH_CAPACITY", "ORDER_RUSH", orderId, Security.PLANNING,
                        "计划部校验急插单 " + orderNo + " 的产能是否可支持（不足直接驳回）",
                        0, 1, rushPayload);
                flow.createTask("RUSH_CONFIRM", "ORDER_RUSH", orderId, Security.BUSINESS,
                        "业务部确认客户书面同意原订单顺延：" + orderNo + "（不同意则不得执行插单）",
                        0, 1, rushPayload);
                flow.appendTimeline(orderId, "急插单已登记：等待计划部产能校验 + 业务部客户同意确认，两项通过后才可提交完整评审");
            }
            return created;
        });
    }

    /**
     * 提交订单。优化后的顺序：库存判断前置 —— 齐备则短路直发（财务审核后出库），
     * 不足才进入「财务前置校验 → 并行会签」；急插单必须完成两项前置审批，预测订单走基础校验后直接生效。
     */
    public Map<String, Object> submit(String id, Security.User user) {
        return store.tx(() -> {
            Map<String, Object> order = store.require(Store.ORDERS, id, "销售订单");
            String status = Json.str(order.get("status"), "");
            if (!FlowEngine.DRAFT.equals(status) && !FlowEngine.ORDER_REJECTED.equals(status)) {
                throw Errors.conflict("只有草稿或被驳回的订单可以提交，当前状态: " + status);
            }
            boolean rush = Json.bool(order.get("rush"), false);
            if (rush && !Json.bool(order.get("rushApproved"), false)) {
                throw Errors.conflict("急插单须先完成「计划部产能校验」与「业务部客户同意顺延」两项前置审批，通过后才能提交完整评审");
            }
            boolean forecast = "FORECAST".equals(Json.str(order.get("salesType"), "NORMAL"));
            if (!forecast && !rush) {
                Map<String, Object> inventory = checkInventory(Json.asList(order.get("items")));
                if (Json.bool(inventory.get("allAvailable"), false)) {
                    StateMachine.assertTransition(status, FlowEngine.STOCK_DIRECT);
                    store.update(Store.ORDERS, id, Json.obj(
                            "status", FlowEngine.STOCK_DIRECT, "reviewStage", 99,
                            "totalStages", 0, "stockShortcut", true, "archived", true));
                    Map<String, Object> dn = delivery.createStockDirectFromOrder(
                            store.get(Store.ORDERS, id), user);
                    return Json.obj("order", store.get(Store.ORDERS, id), "createdTasks", 1,
                            "tasks", List.of(), "shortcut", true, "deliveryNote", dn,
                            "submittedBy", user.username,
                            "message", "库存齐备：按优化后流程跳过评审链，已生成《发货单》并提交财务部审核");
                }
            }
            List<Map<String, Object>> tasks = flow.startOrderReview(order);
            Map<String, Object> updated = store.get(Store.ORDERS, id);
            return Json.obj("order", updated, "createdTasks", tasks.size(),
                    "tasks", tasks, "shortcut", false, "submittedBy", user.username,
                    "message", forecast ? "预测销售：基础校验通过，免签字评审直接生效"
                            : "库存不足：先由财务部校验价格/信用/收款条款，通过后进入并行评审");
        });
    }

    /** 5.6：生产完成 → 入库 → 方可发货。 */
    public Map<String, Object> produce(String id, Security.User user) {
        Map<String, Object> order = store.require(Store.ORDERS, id, "销售订单");
        if (!FlowEngine.ORDER_APPROVED.equals(order.get("status"))) {
            throw Errors.conflict("订单须评审通过归档后才能安排生产");
        }
        StateMachine.assertTransition(Json.str(order.get("status"), ""), FlowEngine.IN_PRODUCTION);
        Map<String, Object> updated = store.update(Store.ORDERS, id,
                Json.obj("status", FlowEngine.IN_PRODUCTION, "producedAt", Ids.now()));
        flow.appendTimeline(id, "计划部安排生产，订单进入生产中");
        return updated;
    }

    public Map<String, Object> inStock(String id, Security.User user) {
        Map<String, Object> order = store.require(Store.ORDERS, id, "销售订单");
        if (!FlowEngine.IN_PRODUCTION.equals(order.get("status"))) {
            throw Errors.conflict("订单须先进入生产中才能办理入库");
        }
        StateMachine.assertTransition(Json.str(order.get("status"), ""), FlowEngine.IN_STOCK);
        return store.tx(() -> {
            // 完工入库同步增加可用库存，后续《发货单》出库才有实物可扣
            for (Object o : Json.asList(order.get("items"))) {
                Map<String, Object> item = Json.asMap(o);
                String pid = Json.str(item.get("productId"), "");
                int qty = Json.integer(item.get("quantity"), 0);
                Map<String, Object> product = store.get(Store.PRODUCTS, pid);
                if (product != null) {
                    store.update(Store.PRODUCTS, pid,
                            Json.obj("stock", Json.integer(product.get("stock"), 0) + qty));
                }
                store.insert(Store.INVENTORY_LOGS, Json.obj("productId", pid,
                        "productName", item.get("productName"), "quantity", qty, "direction", "IN",
                        "orderNo", order.get("orderNo"), "operator", user.username, "at", Ids.now()));
            }
            Map<String, Object> updated = store.update(Store.ORDERS, id,
                    Json.obj("status", FlowEngine.IN_STOCK, "inStockAt", Ids.now()));
            flow.appendTimeline(id, "生产完成并办理入库，库存已更新，业务员可按合同交期发货");
            return updated;
        });
    }

    /** 5.4.2：计划部无法按期交货，需提前三天填写《交期延期通知单》并交业务部沟通。 */
    public Map<String, Object> createDelayNotice(String id, Map<String, Object> input, Security.User user) {
        return store.tx(() -> {
            Map<String, Object> order = store.require(Store.ORDERS, id, "销售订单");
            String deliveryDate = Json.str(order.get("deliveryDate"), "");
            int left = deliveryDate.isBlank() ? 0 : Ids.workdaysBetween(LocalDate.now(), Ids.parseDate(deliveryDate));
            if (left < 3) throw Errors.validation("5.4.2 需在交期基础上提前至少 3 个工作日提出延期，当前剩余 " + left + " 个工作日");
            String newDate = required(input, "newDeliveryDate");
            Ids.parseDate(newDate);
            // 优化：变更/延期修改交期同样强制校验《产品生产周期表》，与新建订单一致
            rules.assertDeliveryCycle(Json.asList(order.get("items")), newDate, "《交期延期通知单》");
            Map<String, Object> change = store.insert(Store.CHANGES, Json.obj(
                    "changeNo", Ids.next("DLY"), "orderId", id, "type", "DELAY_NOTICE",
                    "reason", Json.str(input.get("reason"), "计划部无法按评审交期交货"),
                    "oldDeliveryDate", deliveryDate, "newDeliveryDate", newDate,
                    "details", "《交期延期通知单》：提前 " + left + " 个工作日提出",
                    "status", "PENDING", "createdBy", user.username));
            flow.createTask("DELAY_NOTICE", "ORDER_CHANGE", Json.str(change.get("id"), ""),
                    Security.BUSINESS, "业务部与客户沟通交期延期：" + Json.str(order.get("orderNo"), id),
                    1, 1, Json.obj("changeNo", change.get("changeNo"), "orderId", id));
            flow.appendTimeline(id, "计划部提前发出《交期延期通知单》，待业务部与客户沟通");
            return change;
        });
    }

    private static String required(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null || String.valueOf(v).isBlank()) throw Errors.validation("缺少必填字段: " + key);
        return String.valueOf(v).trim();
    }
}
