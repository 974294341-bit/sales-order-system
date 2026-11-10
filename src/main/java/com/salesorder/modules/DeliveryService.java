package com.salesorder.modules;

import com.salesorder.platform.Errors;
import com.salesorder.platform.Ids;
import com.salesorder.platform.Json;
import com.salesorder.platform.Security;
import com.salesorder.platform.Store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 5.1 有库存直开《发货单》→ 财务审核 → 仓库出库；5.6 订单完工入库后按合同交期发货。 */
public final class DeliveryService {
    private final Store store;
    private final FlowEngine flow;

    public DeliveryService(Store store, FlowEngine flow) {
        this.store = store;
        this.flow = flow;
    }

    public List<Map<String, Object>> list(String status) {
        List<Map<String, Object>> out = store.find(Store.DELIVERY_NOTES,
                d -> status == null || status.isBlank() || status.equals(d.get("status")));
        out.sort(Comparator.comparing((Map<String, Object> d) -> Json.str(d.get("createdAt"), "")).reversed());
        return out;
    }

    public Map<String, Object> get(String id) {
        return store.require(Store.DELIVERY_NOTES, id, "发货单");
    }

    public Map<String, Object> create(Map<String, Object> input, Security.User user) {
        String orderId = Json.str(input.get("orderId"), "");
        List<Object> items = new ArrayList<>();
        String customerId;
        String customerName;
        String contractDate;

        if (!orderId.isBlank()) {
            Map<String, Object> order = store.require(Store.ORDERS, orderId, "销售订单");
            if (FlowEngine.STOCK_DIRECT.equals(order.get("status"))) {
                throw Errors.conflict("库存优先短路订单已自动生成《发货单》，请勿重复开具");
            }
            if (!FlowEngine.IN_STOCK.equals(order.get("status"))) {
                throw Errors.conflict("5.6 订单须先安排生产并办理入库后才能发货，当前状态: " + order.get("status"));
            }
            items.addAll(Json.asList(order.get("items")));
            customerId = Json.str(order.get("customerId"), "");
            customerName = Json.str(order.get("customerName"), "");
            contractDate = Json.str(order.get("deliveryDate"), Ids.today());
        } else {
            customerId = Json.str(input.get("customerId"), "");
            if (customerId.isBlank()) throw Errors.validation("直发《发货单》必须指定 customerId");
            Map<String, Object> customer = store.require(Store.CUSTOMERS, customerId, "客户");
            customerName = Json.str(customer.get("name"), "");
            contractDate = Json.str(input.get("contractDeliveryDate"), Ids.date(Ids.plusWorkdays(7)));
            List<Object> raw = input.get("items") instanceof List<?> ? Json.asList(input.get("items")) : List.of();
            if (raw.isEmpty()) throw Errors.validation("发货明细不能为空");
            for (Object o : raw) {
                Map<String, Object> rawItem = Json.asMap(o);
                String pid = Json.str(rawItem.get("productId"), "");
                Map<String, Object> product = store.require(Store.PRODUCTS, pid, "产品");
                int qty = Json.integer(rawItem.get("quantity"), 0);
                if (qty <= 0) throw Errors.validation("发货数量必须大于 0");
                if (Json.integer(product.get("stock"), 0) < qty) {
                    throw Errors.validation("产品 " + product.get("name") + " 库存不足，不能直开发货单");
                }
                items.add(Json.obj("productId", pid, "productName", product.get("name"),
                        "quantity", qty, "price", product.get("price"), "unit", product.get("unit")));
            }
        }

        Map<String, Object> note = Json.obj(
                "dnNo", Ids.next("DN"), "orderId", orderId,
                "source", orderId.isBlank() ? "STOCK_DIRECT" : "ORDER_SHIPMENT",
                "customerId", customerId, "customerName", customerName,
                "contractDeliveryDate", contractDate, "items", items,
                "status", "CREATED", "onTime", null,
                "createdBy", user.username, "createdByName", user.name,
                "remark", Json.str(input.get("remark"), ""));

        return store.tx(() -> saveNote(note, items, user, "按合同交期开具《发货单》"));
    }

    /**
     * 优化：库存优先短路。订单提交时若库存齐备，跳过工程/计划/业务全套评审，
     * 直接生成《发货单》→ 财务部审核 → 仓库出库。调用方需处于事务内。
     */
    public Map<String, Object> createStockDirectFromOrder(Map<String, Object> order, Security.User user) {
        String orderId = Json.str(order.get("id"), "");
        List<Object> items = Json.asList(order.get("items"));
        Map<String, Object> note = Json.obj(
                "dnNo", Ids.next("DN"), "orderId", orderId, "source", "STOCK_DIRECT_SHORTCUT",
                "customerId", order.get("customerId"), "customerName", order.get("customerName"),
                "contractDeliveryDate", Json.str(order.get("deliveryDate"), Ids.today()),
                "items", items, "status", "CREATED", "onTime", null,
                "createdBy", user.username, "createdByName", user.name,
                "remark", "库存优先短路：跳过订单评审，财务审核后直接出库");
        return saveNote(note, items, user, "库存齐备，跳过《销售订单》评审链，直接生成《发货单》");
    }

    private Map<String, Object> saveNote(Map<String, Object> note, List<Object> items,
                                         Security.User user, String timelineText) {
        Map<String, Object> created = store.insert(Store.DELIVERY_NOTES, note);
        flow.createTask("DELIVERY_FINANCE", "DELIVERY_NOTE", Json.str(created.get("id"), ""),
                Security.FINANCE, "财务部审核《发货单》 " + created.get("dnNo")
                        + "（价格/收款条款/客户信用）", 1, 1,
                Json.obj("dnNo", created.get("dnNo"), "amount", sumAmount(items),
                        "customerName", note.get("customerName")));
        String orderId = Json.str(note.get("orderId"), "");
        if (!orderId.isBlank()) {
            flow.appendTimeline(orderId, timelineText + " " + created.get("dnNo") + "，待财务部审核");
        }
        return created;
    }

    private double sumAmount(List<Object> items) {
        double total = 0;
        for (Object o : items) {
            Map<String, Object> item = Json.asMap(o);
            total += Json.dbl(item.get("price"), 0) * Json.integer(item.get("quantity"), 0);
        }
        return total;
    }
}
