package com.salesorder.modules;

import com.salesorder.platform.Errors;

import java.util.Map;
import java.util.Set;

/**
 * 集中式订单状态机：每个流转动作只允许从指定状态跳转，非法跳转在边界处直接拦截。
 * 优化点：原方案未定义状态机，开发时容易出现状态混乱；这里把可跳转关系收敛到一处。
 */
public final class StateMachine {
    private StateMachine() {}

    private static final Map<String, Set<String>> TRANSITIONS = Map.ofEntries(
            Map.entry(FlowEngine.DRAFT, Set.of(FlowEngine.IN_REVIEW, FlowEngine.STOCK_DIRECT,
                    FlowEngine.ORDER_APPROVED, "CANCELLED")),
            Map.entry(FlowEngine.STOCK_DIRECT, Set.of(FlowEngine.SHIPPED, "CHANGING", "CLOSED")),
            Map.entry(FlowEngine.IN_REVIEW, Set.of(FlowEngine.ORDER_APPROVED, FlowEngine.ORDER_REJECTED, "CHANGING")),
            Map.entry(FlowEngine.ORDER_REJECTED, Set.of(FlowEngine.IN_REVIEW, "CANCELLED")),
            Map.entry(FlowEngine.ORDER_APPROVED, Set.of(FlowEngine.IN_PRODUCTION, "CHANGING", "CLOSED")),
            Map.entry("CHANGING", Set.of(FlowEngine.ORDER_APPROVED, FlowEngine.IN_PRODUCTION,
                    FlowEngine.IN_STOCK, FlowEngine.STOCK_DIRECT, FlowEngine.IN_REVIEW, "CANCELLED")),
            Map.entry(FlowEngine.IN_PRODUCTION, Set.of(FlowEngine.IN_STOCK, "CHANGING")),
            Map.entry(FlowEngine.IN_STOCK, Set.of(FlowEngine.SHIPPED, "CHANGING")),
            Map.entry(FlowEngine.SHIPPED, Set.of("CLOSED")),
            Map.entry("CLOSED", Set.of()),
            Map.entry("CANCELLED", Set.of()));

    /** 校验状态跳转合法性；未知状态一律拒绝，避免脏数据。 */
    public static void assertTransition(String from, String to) {
        Set<String> allowed = TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw Errors.conflict("非法状态跳转：" + (from == null ? "空" : from) + " → " + to
                    + "，请按流程顺序操作");
        }
    }

    /**
     * 预测销售订单专用：豁免多部门签字评审，但只允许草稿/驳回态直接生效。
     * 基础业务校验（样品、试产、BOM、生产周期）由建单环节强制完成，不属于状态机职责。
     */
    public static void assertForecastDirect(String from) {
        if (!FlowEngine.DRAFT.equals(from) && !FlowEngine.ORDER_REJECTED.equals(from)) {
            throw Errors.conflict("预测销售订单只能从草稿或被驳回状态直接生效，当前状态: " + from);
        }
        assertTransition(from, FlowEngine.ORDER_APPROVED);
    }
}
