# -*- coding: utf-8 -*-
"""销售订货系统端到端冒烟测试（纯标准库，无第三方依赖）。

前置：服务已启动在 127.0.0.1:8080，且 data/store.json 为全新或可重复运行的数据集。
运行：python scripts/smoke_test.py
覆盖：认证、元数据、库存短路、财务前置、并行会签、变更类型分派与版本历史、
      急插单前置、联络单闭环、预测基础校验、周期表受控发行、发货与交付率、
      SLA 升级、移动端权限、审批字段白名单。
"""
import http.client
import json
import sys

HOST, PORT = "127.0.0.1", 8080
DELIVERY_DATE = "2026-12-20"  # 距 2026-11-10 约 28 个工作日，满足所有产品生产周期
PASS, FAIL = [], []


def req(method, path, body=None, token=None, client=None):
    conn = http.client.HTTPConnection(HOST, PORT, timeout=15)
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if client:
        headers["X-Client"] = client
    payload = json.dumps(body if body is not None else {}, ensure_ascii=False).encode("utf-8")
    conn.request(method, path, payload, headers)
    resp = conn.getresponse()
    raw = resp.read().decode("utf-8")
    conn.close()
    try:
        data = json.loads(raw) if raw else {}
    except ValueError:
        data = {"raw": raw}
    return resp.status, data


def check(name, cond, detail=""):
    (PASS if cond else FAIL).append(name)
    print(("  PASS  " if cond else "  FAIL  ") + name + (("  -> " + str(detail)[:300]) if not cond else ""))


def must(status, data, expect=(200, 201)):
    if status not in expect:
        raise RuntimeError("HTTP %s: %s" % (status, json.dumps(data, ensure_ascii=False)[:400]))
    return data


def login(username, client=None):
    status, data = req("POST", "/api/auth/login",
                       {"username": username, "password": "123456"}, client=client)
    return must(status, data)["accessToken"]


def tasks(token, role, task_type=None, biz_type=None, status="PENDING"):
    q = "/api/approvals?role=%s&status=%s" % (role, status)
    s, data = req("GET", q, token=token)
    must(s, data)
    out = data["items"]
    if task_type:
        out = [t for t in out if t["type"] == task_type]
    if biz_type:
        out = [t for t in out if t["bizType"] == biz_type]
    return out


def decide(token, task_id, approve=True, extra=None, path_suffix=None):
    suffix = path_suffix or ("/approve" if approve else "/reject")
    body = {"approve": approve, "comment": "冒烟测试自动审批"}
    if extra:
        body["extra"] = extra
    return req("POST", "/api/approvals/" + task_id + suffix, body, token=token)


def one(rows, label):
    if not rows:
        raise RuntimeError("找不到待办: " + label)
    return rows[0]


def main():
    admin = login("admin")
    biz = login("business")
    manager = login("manager")
    engineer = login("engineer")
    planner = login("planner")
    finance = login("finance")
    warehouse = login("warehouse")
    purchase = login("purchase")
    quality = login("quality")
    leader = login("leader")
    docctl = login("docctl")
    print("\n[1] 认证与元数据")
    check("11 个岗位账号全部可登录", True)
    s, meta = req("GET", "/api/meta", token=admin)
    must(s, meta)
    check("元数据含岗位权责表", len(meta.get("responsibilities", [])) >= 10)
    check("元数据含改进点对照", len(meta.get("improvements", [])) >= 14)
    check("元数据含优化后流程", len(meta.get("flowOverview", [])) == 11)
    check("元数据含移动端权限边界", bool(meta.get("mobilePermissions", {}).get("denied")))
    check("元数据含状态标签 STOCK_DIRECT", "STOCK_DIRECT" in meta["orderStatusLabels"])

    print("\n[2] 库存优先短路：齐备则跳过评审，直发财务审核")
    s, d = req("POST", "/api/orders", {
        "customerId": "C001", "deliveryDate": DELIVERY_DATE,
        "items": [{"productId": "P001", "quantity": 20, "price": 320}]
    }, token=biz)
    direct = must(s, d)
    s, d = req("POST", "/api/orders/" + direct["id"] + "/submit", {}, token=biz)
    r = must(s, d)
    check("库存齐备进入 STOCK_DIRECT", r["order"]["status"] == "STOCK_DIRECT", r["order"]["status"])
    check("短路自动生成发货单", bool(r.get("deliveryNote")))
    check("短路不生成评审任务", r["createdTasks"] == 1 and not r["tasks"])
    dn_shortcut = r["deliveryNote"]
    s, d = req("POST", "/api/approvals/" +
               one(tasks(admin, "FINANCE", "DELIVERY_FINANCE"), "发货财务审核")["id"] +
               "/approve", {"approve": True, "comment": "价格与收款条款已核"}, token=finance)
    must(s, d)
    s, d = req("POST", "/api/approvals/" +
               one(tasks(admin, "WAREHOUSE", "DELIVERY_WAREHOUSE"), "仓库出库")["id"] +
               "/approve", {"approve": True, "comment": "已出库"}, token=warehouse)
    must(s, d)
    s, d = req("GET", "/api/orders/" + direct["id"], token=admin)
    must(s, d)
    check("短路订单出库后为 SHIPPED", d["status"] == "SHIPPED", d["status"])

    print("\n[3] 库存不足：财务前置 → 并行会签 → 业务确认归档")
    s, d = req("POST", "/api/orders", {
        "customerId": "C003", "deliveryDate": DELIVERY_DATE,
        "items": [{"productId": "P002", "quantity": 30, "price": 480}]
    }, token=biz)
    order = must(s, d)
    s, d = req("POST", "/api/orders/" + order["id"] + "/submit", {}, token=biz)
    r = must(s, d)
    check("库存不足进入 IN_REVIEW", r["order"]["status"] == "IN_REVIEW", r["order"]["status"])
    check("先生成财务前置任务", r["createdTasks"] == 1 and r["tasks"][0]["type"] == "ORDER_FINANCE")
    s, d = decide(finance, one(tasks(admin, "FINANCE", "ORDER_FINANCE", "ORDER"), "财务前置")["id"],
                  extra={"creditOk": True, "priceOk": True, "paymentTerms": "月结 30 天"})
    must(s, d)
    st1 = tasks(admin, "", biz_type="ORDER")
    check("财务通过后并行生成工程+计划任务",
          {"ORDER_BOM", "ORDER_DELIVERY"}.issubset({t["type"] for t in st1}),
          [t["type"] for t in st1])
    must(*decide(engineer, one(tasks(admin, "ENGINEERING", "ORDER_BOM"), "工程 BOM")["id"],
                 extra={"bomNote": "BOM 已复核"}))
    s, d = req("GET", "/api/orders/" + order["id"], token=admin)
    must(s, d)
    check("并行会签未完成时不推进阶段", d["reviewStage"] == 1, d["reviewStage"])
    must(*decide(planner, one(tasks(admin, "PLANNING", "ORDER_DELIVERY"), "计划交期")["id"],
                 extra={"plannedFinishDate": DELIVERY_DATE, "deliveryReply": "按期完工"}))
    s, d = req("GET", "/api/orders/" + order["id"], token=admin)
    must(s, d)
    check("并行全部通过进入第 2 阶段", d["reviewStage"] == 2, d["reviewStage"])
    must(*decide(biz, one(tasks(admin, "BUSINESS", "ORDER_CONFIRM"), "业务确认")["id"],
                 extra={"archiveNo": "ARCH-2026-001"}))
    s, d = req("GET", "/api/orders/" + order["id"], token=admin)
    must(s, d)
    check("评审链完成后 APPROVED 且已归档", d["status"] == "APPROVED" and d["archived"] is True, d["status"])

    print("\n[3b] 非标产品：BOM 初稿后工程/计划/采购/品质四部门并行会签")
    s, d = req("POST", "/api/orders", {
        "customerId": "C002", "deliveryDate": DELIVERY_DATE, "engineeringConfirmed": True,
        "items": [{"productId": "P003", "quantity": 5, "price": 2600}]
    }, token=biz)
    ns = must(s, d)
    s, d = req("POST", "/api/orders/" + ns["id"] + "/submit", {}, token=biz)
    r = must(s, d)
    check("非标订单识别为 3 阶段", r["order"]["totalStages"] == 3, r["order"]["totalStages"])
    must(*decide(finance, one(tasks(admin, "FINANCE", "ORDER_FINANCE", "ORDER"), "非标财务前置")["id"],
                 extra={"creditOk": True, "priceOk": True}))
    must(*decide(engineer, one(tasks(admin, "ENGINEERING", "ORDER_BOM", "ORDER"), "非标 BOM 初稿")["id"],
                 extra={"bomNote": "非标 BOM 初稿已完成"}))
    stage2 = {t["type"] for t in tasks(admin, "", biz_type="ORDER")}
    check("非标 stage2 四部门并行会签",
          {"ORDER_COSIGN_ENGINEERING", "ORDER_DELIVERY", "ORDER_COSIGN_PURCHASE",
           "ORDER_COSIGN_QUALITY"}.issubset(stage2), sorted(stage2))

    print("\n[4] 变更类型分派 + 交期周期表校验 + 版本历史")
    s, d = req("POST", "/api/changes", {
        "orderId": order["id"], "type": "QUANTITY", "reason": "客户追加数量",
        "newDeliveryDate": DELIVERY_DATE, "amountDelta": 4800, "details": "数量 +10"
    }, token=biz)
    change = must(s, d)
    check("数量变更按类型分派计划+财务", set(change.get("reviewers", [])) == {"财务部", "计划部"},
          change.get("reviewers"))
    must(*decide(planner, one(tasks(admin, "PLANNING", "CHANGE_REVIEW", "ORDER_CHANGE"), "变更计划")["id"]))
    s, d = req("GET", "/api/changes/" + change["id"], token=admin)
    must(s, d)
    check("单条会签通过时不执行变更", d["status"] == "PENDING", d["status"])
    must(*decide(finance, one(tasks(admin, "FINANCE", "CHANGE_FINANCE"), "变更金额")["id"],
                 extra={"costNote": "金额变动已确认"}))
    s, d = req("GET", "/api/orders/" + order["id"], token=admin)
    must(s, d)
    check("变更执行后版本 v2", d["version"] == 2, d["version"])
    check("变更历史保留前后快照", len(d.get("changeHistory", [])) == 1)
    check("变更金额已同步", abs(float(d["amount"]) - (30 * 480 + 4800)) < 0.01, d["amount"])
    s, d = req("POST", "/api/changes", {
        "orderId": order["id"], "type": "DELIVERY", "reason": "测试非法交期",
        "newDeliveryDate": "2026-11-12"
    }, token=biz)
    check("变更交期也强制校验生产周期表", s == 422 and
          d.get("error", {}).get("code") == "VALIDATION_FAILED", (s, d))

    print("\n[5] 生产入库 → 发货 → 准时交付率（口径与分组）")
    must(*req("POST", "/api/orders/" + order["id"] + "/produce", {}, token=planner))
    must(*req("POST", "/api/orders/" + order["id"] + "/in-stock", {}, token=planner))
    s, d = req("POST", "/api/delivery-notes", {"orderId": order["id"]}, token=biz)
    dn = must(s, d)
    must(*decide(finance, one(tasks(admin, "FINANCE", "DELIVERY_FINANCE"), "发货财务")["id"]))
    must(*decide(warehouse, one(tasks(admin, "WAREHOUSE", "DELIVERY_WAREHOUSE"), "仓库出库")["id"]))
    s, d = req("GET", "/api/reports/delivery-rate?month=2026-11", token=admin)
    report = must(s, d)
    check("交付率报告含口径说明", "最新约定交期" in report.get("basis", ""), report.get("basis"))
    check("交付率按三组分开统计", len(report.get("byGroup", [])) == 3)
    check("交付率统计到已发货单据", report["total"] >= 2, report["total"])

    print("\n[6] 急插单：产能校验 + 客户同意 + 月度上限 + 完整评审")
    s, d = req("POST", "/api/orders", {
        "customerId": "C001", "deliveryDate": DELIVERY_DATE, "rush": True,
        "relatedOrderId": direct["id"],
        "items": [{"productId": "P002", "quantity": 10, "price": 480}]
    }, token=biz)
    rush = must(s, d)
    check("急插单生成两条前置任务", len(tasks(admin, "", biz_type="ORDER_RUSH")) == 2)
    s, d = req("POST", "/api/orders/" + rush["id"] + "/submit", {}, token=biz)
    check("前置未通过时禁止提交评审", s == 409, (s, d))
    must(*decide(planner, one(tasks(admin, "PLANNING", "RUSH_CAPACITY", "ORDER_RUSH"), "产能校验")["id"],
                 extra={"capacityNote": "产能可支持"}))
    must(*decide(biz, one(tasks(admin, "BUSINESS", "RUSH_CONFIRM", "ORDER_RUSH"), "客户同意")["id"],
                 extra={"customerAgreedNote": "客户书面同意顺延"}))
    s, d = req("GET", "/api/orders/" + rush["id"], token=admin)
    must(s, d)
    check("两项前置通过后允许插单", d["rushApproved"] is True)
    s, d = req("GET", "/api/changes?orderId=" + direct["id"], token=admin)
    must(s, d)
    check("客户同意后才发出书面顺延通知", any(c["type"] == "RUSH_NOTICE" for c in d["items"]))
    s, d = req("POST", "/api/orders/" + rush["id"] + "/submit", {}, token=biz)
    r = must(s, d)
    check("急插单仍执行完整评审（不跳过）", r["order"]["status"] == "IN_REVIEW" and
          r["tasks"][0]["type"] == "ORDER_FINANCE", r["order"]["status"])

    print("\n[7] 联络单闭环：审批 → 计划处理 → 业务确认 → 归档")
    s, d = req("POST", "/api/liaisons", {"title": "样品包装确认", "content": "请计划部协调包装打样"}, token=biz)
    liaison = must(s, d)
    must(*decide(manager, one(tasks(admin, "BUSINESS_MANAGER", "LIAISON_APPROVE", "LIAISON"), "联络单审批")["id"]))
    s, d = req("GET", "/api/liaisons/" + liaison["id"], token=admin)
    must(s, d)
    check("审批后进入待计划处理", d["status"] == "WAIT_PLANNING", d["status"])
    must(*decide(planner, one(tasks(admin, "PLANNING", "LIAISON_HANDLE"), "计划处理")["id"],
                 extra={"handleResult": "已安排打样"}))
    s, d = req("GET", "/api/liaisons/" + liaison["id"], token=admin)
    must(s, d)
    check("处理后回传业务确认", d["status"] == "WAIT_CONFIRM", d["status"])
    must(*decide(biz, one(tasks(admin, "BUSINESS", "LIAISON_CONFIRM"), "业务确认")["id"]))
    s, d = req("GET", "/api/liaisons/" + liaison["id"], token=admin)
    must(s, d)
    check("业务确认后闭环归档", d["status"] == "CLOSED" and d["archived"] is True, d["status"])

    print("\n[8] 预测订单：PMC 统一录入 + 免签字但不免基础校验")
    s, d = req("POST", "/api/forecasts", {
        "month": "2026-12", "planDate": DELIVERY_DATE,
        "items": [{"productId": "P001", "quantity": 50, "price": 320}]
    }, token=planner)
    fc = must(s, d)
    must(*req("POST", "/api/forecasts/" + fc["id"] + "/submit", {}, token=planner))
    s, d = req("POST", "/api/forecasts", {
        "month": "2026-12", "planDate": DELIVERY_DATE,
        "items": [{"productId": "P004", "quantity": 10, "price": 880}]
    }, token=biz)
    check("业务角色不能自行录入预测计划（统一 PMC）", s == 403, (s, d))
    must(*decide(planner, one(tasks(admin, "PLANNING", "FORECAST_CONVERT", "FORECAST"), "预测转订单")["id"]))
    s, d = req("GET", "/api/forecasts/" + fc["id"], token=admin)
    must(s, d)
    check("预测计划转为销售订单", d["status"] == "CONVERTED" and bool(d.get("convertedOrderId")), d["status"])

    print("\n[9] 生产周期表：半年更新 → 领导审批 → 文控受控发行")
    s, d = req("POST", "/api/cycle-tables", {
        "version": "2026-H2-SMOKE", "rows": {"P001": 8, "P002": 11, "P003": 21, "P004": 16}
    }, token=planner)
    ct = must(s, d)
    must(*decide(leader, one(tasks(admin, "LEADER", "CYCLE_APPROVE", "CYCLE_TABLE"), "周期表审批")["id"]))
    must(*decide(docctl, one(tasks(admin, "DOC_CONTROL", "CYCLE_PUBLISH"), "周期表发行")["id"]))
    s, d = req("GET", "/api/cycle-tables/" + ct["id"], token=admin)
    must(s, d)
    check("周期表已受控发行", d["status"] == "PUBLISHED", d["status"])

    print("\n[10] SLA、手机端权限与审批白名单")
    s, d = req("GET", "/api/sla/overdue", token=admin)
    check("超时清单接口可用", s == 200 and "items" in d)
    s, d = req("POST", "/api/sla/scan", {}, token=planner)
    must(s, d)
    check("SLA 扫描幂等执行", d.get("enabled") is True and "scanned" in d, d)
    s, d = req("POST", "/api/orders", {
        "customerId": "C001", "deliveryDate": DELIVERY_DATE,
        "items": [{"productId": "P001", "quantity": 1}]
    }, token=biz, client="mobile")
    check("手机端禁止创建订单", s == 403, (s, d))
    mobile_manager = login("manager", client="mobile")
    s, d = req("POST", "/api/liaisons", {"title": "移动端审批验证", "content": "仅用于验证手机端审批权限"}, token=biz)
    must(s, d)
    mobile_task = one(tasks(admin, "BUSINESS_MANAGER", "LIAISON_APPROVE", "LIAISON"), "手机端审批")
    s2, d2 = req("POST", "/api/approvals/" + mobile_task["id"] + "/approve",
                 {"approve": True, "comment": "手机端通过"}, token=mobile_manager, client="mobile")
    check("手机端可完成审批", s2 == 200, (s2, d2))
    bad = one(tasks(admin, "", biz_type="ORDER"), "白名单校验任务")
    s, d = decide(admin, bad["id"], extra={"status": "SHIPPED", "deliveryDate": "2026-11-11"})
    check("审批接口拒绝篡改订单字段", s == 422, (s, d))

    print("\n[11] 前端静态资源")
    for p in ("/", "/m.html", "/app.js", "/views.js", "/m.js", "/style.css", "/api.js"):
        s, _ = req("GET", p)
        check("静态资源 " + p + " 可访问", s == 200, s)

    print("\n===== 冒烟结果：%d 通过 / %d 失败 =====" % (len(PASS), len(FAIL)))
    if FAIL:
        print("失败项：" + "、".join(FAIL))
        return 1
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:
        print("\n冒烟中断：" + str(e))
        sys.exit(2)
