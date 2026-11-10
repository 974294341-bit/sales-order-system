package com.salesorder.modules;

import com.salesorder.platform.Config;
import com.salesorder.platform.Errors;
import com.salesorder.platform.Json;
import com.salesorder.platform.Security;
import com.salesorder.platform.Store;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 基础资料与界面元数据：岗位分工导航由后端下发，前端按当前角色过滤，保证分工清晰。 */
public final class MasterDataService {
    private final Store store;
    private final Config config;

    public MasterDataService(Store store, Config config) {
        this.store = store;
        this.config = config;
    }

    public List<Map<String, Object>> products() {
        List<Map<String, Object>> out = store.list(Store.PRODUCTS);
        out.sort(Comparator.comparing(m -> Json.str(m.get("code"), "")));
        return out;
    }

    public List<Map<String, Object>> customers() {
        List<Map<String, Object>> out = store.list(Store.CUSTOMERS);
        out.sort(Comparator.comparing(m -> Json.str(m.get("code"), "")));
        return out;
    }

    public List<Map<String, Object>> inventoryLogs() {
        List<Map<String, Object>> out = store.list(Store.INVENTORY_LOGS);
        out.sort(Comparator.comparing((Map<String, Object> m) -> Json.str(m.get("at"), "")).reversed());
        return out;
    }

    public Map<String, Object> createProduct(Map<String, Object> input) {
        String code = Json.str(input.get("code"), "");
        String name = Json.str(input.get("name"), "");
        if (code.isBlank() || name.isBlank()) throw Errors.validation("产品编码与名称不能为空");
        Map<String, Object> product = Json.obj(
                "id", code, "code", code, "name", name,
                "standard", Json.bool(input.get("standard"), true),
                "sampleApproved", Json.bool(input.get("sampleApproved"), true),
                "trialPassed", Json.bool(input.get("trialPassed"), true),
                "bomReady", Json.bool(input.get("bomReady"), true),
                "cycleDays", Json.integer(input.get("cycleDays"), 7),
                "stock", Json.integer(input.get("stock"), 0),
                "unit", Json.str(input.get("unit"), "PCS"),
                "price", Json.dbl(input.get("price"), 0));
        return store.insert(Store.PRODUCTS, product);
    }

    public Map<String, Object> createCustomer(Map<String, Object> input) {
        String code = Json.str(input.get("code"), "");
        String name = Json.str(input.get("name"), "");
        if (code.isBlank() || name.isBlank()) throw Errors.validation("客户编码与名称不能为空");
        return store.insert(Store.CUSTOMERS, Json.obj(
                "id", code, "code", code, "name", name,
                "type", Json.str(input.get("type"), "DOMESTIC"),
                "fixed", Json.bool(input.get("fixed"), false),
                "contact", Json.str(input.get("contact"), "")));
    }

    /** 前端界面元数据：禅道式分组导航 + 10 个功能模块 + 状态字典 + 优化后的权责规则。 */
    public Map<String, Object> meta(Security.User user) {
        List<Object> roles = new java.util.ArrayList<>();
        for (String r : Security.ALL_ROLES) {
            roles.add(Json.obj("key", r, "label", Security.roleLabel(r)));
        }

        List<Object> groups = Json.arr(
                group("我的地盘", "home", Json.arr("*"),
                        item("dashboard", "工作台", "我的待办、流程进度与经营概览", "dashboard"),
                        item("approvals", "我的审批", "按当前岗位过滤的待办与已办", "approvals"),
                        item("sla", "超时与升级", "全节点 SLA 时限、逾期清单与主管升级", "sla"),
                        item("blueprint", "优化后业务框架", "岗位职责、流程与规则改进对照", "blueprint")),
                group("业务部 / 市场部", "sales", Json.arr(Security.BUSINESS, Security.ADMIN),
                        item("intake", "接单与库存", "5.1 接单预判、库存直发", "intake"),
                        item("orders", "销售订单", "5.2/5.3 订单内容与评审链", "orders"),
                        item("delivery", "发货单", "5.1/5.6 财务审核与出库", "delivery"),
                        item("changes", "订单变更", "5.4 变更单与 5.5 急插单", "changes"),
                        item("liaisons", "联络单", "5.7 非订单类需求", "liaisons")),
                group("工程部", "engineering", Json.arr(Security.ENGINEERING, Security.ADMIN),
                        item("bom", "BOM 评审", "5.3.1 制定/复核订单 BOM 用料清单", "approvals")),
                group("计划部", "planning", Json.arr(Security.PLANNING, Security.ADMIN),
                        item("forecasts", "预测需求计划", "5.8 月度预测转销售订单", "forecasts"),
                        item("reports", "准时交付率", "5.9 月度统计与改善措施", "reports"),
                        item("cycles", "生产周期表", "5.10 半年更新与受控发行", "cycles")),
                group("财务部 / 仓库", "finance", Json.arr(Security.FINANCE, Security.WAREHOUSE, Security.ADMIN),
                        item("shipapproval", "发货审核与出库", "财务前置校验、发货审核、仓库出库", "approvals")),
                group("采购 / 品质", "cosign", Json.arr(Security.PURCHASING, Security.QUALITY, Security.ADMIN),
                        item("cosign", "非标会签", "非标产品物料齐套与检验标准并行会签", "approvals")),
                group("领导 / 文控", "docs", Json.arr(Security.LEADER, Security.DOC_CONTROL, Security.ADMIN),
                        item("cycleapproval", "周期表审批发行", "分管领导审批、文控受控发行", "approvals")),
                group("组织与统计", "system", Json.arr(Security.ADMIN, Security.BUSINESS_MANAGER),
                        item("products", "产品与客户", "基础资料维护", "products"),
                        item("roles", "岗位分工", "角色权责表与流程说明", "roles")));

        List<Object> modules = Json.arr(
                module("5.1", "接单与库存判断", "库存优先：齐备短路直发，不足才进评审"),
                module("5.2", "订单内容规范", "样品/试产/BOM/交期/附页/待确认需求校验"),
                module("5.3", "订单评审流程", "财务前置 + 工程/计划并行 + 非标采购品质会签"),
                module("5.4", "订单变更处理", "类型分派会签、版本历史、交期周期表校验、财务金额确认"),
                module("5.5", "急插单", "7 工作日 + 产能校验 + 月度上限 + 客户同意顺延 + 完整评审"),
                module("5.6", "完工入库与发货", "生产→入库→按最新约定交期发货"),
                module("5.7", "联络单", "审批→计划处理→业务确认→闭环归档"),
                module("5.8", "预测需求计划", "业务输出、PMC 统一录入；免签字但不免基础校验"),
                module("5.9", "准时交付率", "最新约定交期为基准，正常/急插/预测分组统计"),
                module("5.10", "产品生产周期表", "半年更新→领导审批→文控受控发行"));

        // 优化：明确每个岗位的创建人 / 审核人 / 归档人 / 时限 / 超时升级对象
        List<Object> responsibilities = Json.arr(
                resp("BUSINESS", "市场业务员 / 销售助理", "录入销售订单、变更单、联络单、发货单；确认客户交期",
                        "销售订单、变更单、联络单、发货单", "业务部确认交期、联络单处理结果", "纸质评审文档协同归档",
                        "1 个工作日", "业务部负责人"),
                resp("BUSINESS_MANAGER", "业务部负责人", "审批联络单、接收业务部超时升级",
                        "—", "联络单审批、超时催办", "联络单结案归档", "1 个工作日", "分管领导"),
                resp("ENGINEERING", "工程工程师", "确认产品要求、制定/复核订单 BOM",
                        "订单 BOM 用料清单", "非标 BOM、规格变更", "BOM 评审记录", "≤10 款 1 天，最多顺延 3 天", "分管领导"),
                resp("PLANNING", "PMC 计划员 / 计划主管", "交期评审、产能校验、预测订单统一录入、月度统计",
                        "预测销售订单、准时交付率报告、周期表草稿", "交期、急插单产能、变更、周期表", "计划评审记录与报告归档",
                        "1 个工作日", "分管领导"),
                resp("FINANCE", "财务应收岗", "价格、客户信用额度、收款条款前置校验；发货审核；金额变更确认",
                        "—", "订单财务前置、发货单、数量/规格变更", "财务审核记录", "1 个工作日", "分管领导"),
                resp("PURCHASING", "采购部", "物料齐套评估（非标产品、规格变更）",
                        "—", "非标会签、规格变更会签", "物料评估记录", "1 个工作日", "分管领导"),
                resp("QUALITY", "品质部", "产品检验标准确认",
                        "—", "非标产品检验要求", "检验标准确认记录", "1 个工作日", "分管领导"),
                resp("WAREHOUSE", "仓库", "按发货单办理出库并回写库存",
                        "出库记录", "—", "发货与库存流水", "1 个工作日", "分管领导"),
                resp("DOC_CONTROL", "文控中心", "《产品生产周期表》受控发行",
                        "受控发行记录", "—", "周期表受控版本", "1 个工作日", "分管领导"),
                resp("LEADER", "分管领导", "周期表审批、接收全部部门超时升级",
                        "—", "周期表半年更新、超时催办转派", "审批记录", "2 个工作日", "系统管理员"));

        List<Object> improvements = Json.arr(
                imp("岗位职责", "评审失败无追责、预测订单两处岗位描述冲突、财务未参与订单评审",
                        "岗位权责表落到元数据；预测业务统一由 PMC 录入；订单评审前增加财务前置节点"),
                imp("岗位职责", "非标产品要求采购/品质签字，但岗位职责章节未定义两部门",
                        "新增采购部（物料齐套）、品质部（检验标准）岗位与非标并行会签任务"),
                imp("流程效率", "工程→计划→业务严格串行，采购/品质必须等 BOM 完成",
                        "标准产品工程/计划并行；非标 BOM 初稿后工程、计划、采购、品质并行会签，全部完成才推进"),
                imp("流程效率", "预测订单跳过全部评审，错误预测可直接安排生产",
                        "预测订单豁免签字评审，但样品/试产/BOM/生产周期等基础校验强制拦截"),
                imp("流程效率", "库存判断放在评审之后，库存充足仍跑完整评审链",
                        "库存判断前置：齐备则短路生成发货单走财务审核，不足才进入评审"),
                imp("风险管控", "急插单不校验产能、无上限、客户不同意顺延无分支",
                        "计划部产能校验 + 业务部客户同意确认并行前置，月度上限，驳回即取消插单且不改原单交期"),
                imp("风险管控", "变更不区分类型、无版本历史、无成本确认",
                        "交期/数量/规格三类分派评审人，保存版本前后快照，数量/规格变更增加财务金额确认"),
                imp("风险管控", "联络单计划部处理后无反馈、无结案",
                        "待审批→待计划处理→待业务确认→已结案归档，业务确认后才闭环"),
                imp("业务规则", "周期表只约束新建订单，变更可填小于生产周期的交期",
                        "新建/变更/延期/预测四处共用 BusinessRules 统一校验"),
                imp("业务规则", "BOM 时限统计口径模糊、可无限顺延",
                        "从工程部接收任务当日计时，最多顺延 3 个工作日，超时自动告警主管"),
                imp("业务规则", "准时交付判定口径未定义，变更/插单统计失真",
                        "以变更确认后的最新约定交期为基准，正常/急插/预测分组统计"),
                imp("系统实现", "单据缺少完整状态机，易出现状态混乱",
                        "集中式 StateMachine，非法跳转在边界处直接拦截"),
                imp("系统实现", "手机端权限笼统，可能直接修改订单核心字段",
                        "移动端只保留查看+审批；审批接口字段白名单；改单必须走变更单"),
                imp("系统实现", "评审中途人员离职、单据搁置无人处理",
                        "全部审批节点 SLA 超时扫描，自动升级给部门主管并留痕"));

        List<Object> flowOverview = Json.arr(
                "1. 订单来源：客户销售合同 / 月度预测计划",
                "2. 业务助理录入销售订单，系统校验样品鉴定、试产、BOM 与生产周期表",
                "3. 库存优先短路：齐备 → 财务审核后直接生成发货单出库；不足 → 进入评审",
                "4. 财务前置校验价格、客户信用额度、收款条款，不通过直接退回业务",
                "5. 区分标准/非标订单，自动分派审批人，工程/计划/采购/品质并行会签",
                "6. 各部门按系统计算的时限完成评审，驳回退回业务修改",
                "7. 评审通过归档转生产；预测订单由 PMC 统一录入，免签字但基础校验不豁免",
                "8. 生产完工入库，按最新约定交期开具发货单并出库",
                "9. 发生变更：提交变更通知单，按类型分派会签，保留版本历史并更新订单",
                "10. 急插单：提前 7 个工作日 + 产能校验 + 客户同意顺延 + 月度上限，通过后才走完整评审",
                "11. 每月统计准时交付率；每半年更新生产周期表；联络单闭环归档；超时自动升级");

        Map<String, Object> statusLabels = new LinkedHashMap<>(FlowEngine.ORDER_STATUS_LABELS);
        return Json.obj(
                "appName", "销售订货管理系统",
                "appSubName", "销售订货全流程数字化协同平台",
                "version", "2.0.0",
                "user", user == null ? null : user.view(),
                "roles", roles,
                "navGroups", groups,
                "modules", modules,
                "responsibilities", responsibilities,
                "improvements", improvements,
                "flowOverview", flowOverview,
                "changeTypes", Json.arr(
                        Json.obj("key", "DELIVERY", "label", "交期变更", "reviewers", "计划部"),
                        Json.obj("key", "QUANTITY", "label", "数量变更", "reviewers", "计划部 + 财务部"),
                        Json.obj("key", "SPEC", "label", "产品规格/BOM 变更", "reviewers", "工程部 + 计划部 + 采购部 + 财务部")),
                "rushRules", Json.obj("leadWorkdays", 7, "monthlyLimit", config.rushMonthlyLimit,
                        "monthlyLimitNote", "月度上限由 APP_RUSH_MONTHLY_LIMIT 配置",
                        "prechecks", "计划部产能校验 + 业务部客户同意顺延（并行，全部通过才可提交评审）"),
                "slaEscalateEnabled", config.slaEscalateEnabled,
                "deliveryRateBasis", "以变更确认后的最新约定交期为考核基准（非订单原始交期），正常/急插/预测订单分开统计",
                "slaRules", Json.obj("allNodes", true, "escalateTo", "业务部→业务部负责人；其他部门→分管领导",
                        "bomMaxDelayWorkdays", 3),
                "mobilePermissions", Json.obj(
                        "allowed", "查看单据与流程进度、通过/驳回待办、填写评审意见",
                        "denied", "创建或修改销售订单、发货单、变更单、基础资料；直接修改订单核心字段",
                        "note", "订单内容修改一律走《销售订单变更及通知单》，草稿编辑权限保留在业务 PC 端"),
                "orderStatusLabels", statusLabels,
                "approvalTypeLabels", Json.obj(
                        "ORDER_FINANCE", "财务前置校验", "ORDER_BOM", "工程 BOM 评审",
                        "ORDER_DELIVERY", "计划交期评审", "ORDER_CONFIRM", "业务确认归档",
                        "ORDER_COSIGN_ENGINEERING", "工程 BOM 定稿会签",
                        "ORDER_COSIGN_PURCHASE", "采购会签", "ORDER_COSIGN_QUALITY", "品质会签",
                        "RUSH_CAPACITY", "急插单产能校验", "RUSH_CONFIRM", "客户同意顺延确认",
                        "CHANGE_REVIEW", "变更计划评审", "CHANGE_BOM", "变更规格评审",
                        "CHANGE_FINANCE", "变更金额确认", "CHANGE_COSIGN_PURCHASE", "变更物料会签",
                        "LIAISON_APPROVE", "联络单审批", "LIAISON_HANDLE", "联络单计划处理",
                        "LIAISON_CONFIRM", "联络单结案确认", "DELIVERY_FINANCE", "发货财务审核",
                        "DELIVERY_WAREHOUSE", "仓库出库", "CYCLE_APPROVE", "周期表审批",
                        "CYCLE_PUBLISH", "周期表受控发行", "FORECAST_CONVERT", "预测转订单",
                        "REPORT_REVIEW", "交付率分析", "DELAY_NOTICE", "延期沟通",
                        "SLA_ESCALATION", "超时升级告警"),
                "approvalStatusLabels", Json.obj(
                        "PENDING", "待审批", "APPROVED", "已通过", "REJECTED", "已驳回", "CANCELLED", "已取消"));
    }

    private static Map<String, Object> resp(String key, String label, String duty,
                                            String creates, String reviews, String archives,
                                            String sla, String escalateTo) {
        return Json.obj("key", key, "label", label, "duty", duty, "creates", creates,
                "reviews", reviews, "archives", archives, "sla", sla, "escalateTo", escalateTo);
    }

    private static Map<String, Object> imp(String area, String problem, String solution) {
        return Json.obj("area", area, "problem", problem, "solution", solution);
    }

    private static Map<String, Object> group(String label, String key, List<Object> roles, Object... items) {
        return Json.obj("label", label, "key", key, "roles", roles, "items", Json.arr(items));
    }

    private static Map<String, Object> item(String key, String label, String desc, String tab) {
        return Json.obj("key", key, "label", label, "desc", desc, "tab", tab);
    }

    private static Map<String, Object> module(String no, String name, String desc) {
        return Json.obj("no", no, "name", name, "desc", desc);
    }
}
