/* 各岗位视图：禅道式表格/列表 + 畅捷通式单据表单 */
(function () {
  'use strict';
  var A = window.API, App = window.App, U = App.utils;
  function esc(s) { return A.esc(s); }
  function badge(s, labels) { return U.badge(s, labels); }
  function statusLabel(s) { return App.meta.orderStatusLabels[s] || s; }
  function row(cells) { return '<tr>' + cells.map(function (c) { return '<td>' + c + '</td>'; }).join('') + '</tr>'; }
  function btn(act, label, id, kind, extra) {
    return '<button class="btn ghost ' + (kind || '') + '" data-act="' + act + '"' +
      (id ? ' data-id="' + esc(id) + '"' : '') + (extra || '') + '>' + esc(label) + '</button>';
  }
  function paged(res) { return (res && res.items) || []; }
  var LIAISON_LABELS = {
    PENDING: '待负责人审批', WAIT_PLANNING: '待计划部处理', WAIT_CONFIRM: '待业务部确认结案',
    CLOSED: '已闭环归档', REJECTED: '已驳回', APPLIED: '已执行', COMMUNICATED: '已沟通', NOTIFIED: '已通知'
  };

  /* ---------------- 我的地盘：工作台 ---------------- */
  function orderStatusColor(s) {
    var m = {
      DRAFT: '#8a97a8', STOCK_DIRECT: '#1f6feb', IN_REVIEW: '#1f6feb',
      APPROVED: '#18a058', REJECTED: '#e0484f', IN_PRODUCTION: '#4c8dff',
      IN_STOCK: '#18a058', SHIPPED: '#13b5b1', CHANGING: '#e8901a', CANCELLED: '#cdd7e4'
    };
    return m[s] || '#5a6b80';
  }
  App.register('dashboard', async function (el) {
    var res = await Promise.all([
      App.call('GET', '/api/approvals?status=ALL'),
      App.call('GET', '/api/orders'),
      App.call('GET', '/api/reports'),
      App.call('GET', '/api/delivery-notes'),
      App.call('GET', '/api/analytics')
    ]);
    var tasks = paged(res[0]), orders = paged(res[1]), reports = paged(res[2]), notes = paged(res[3]);
    var ax = res[4];
    var st = res[0].stats || {};
    var pending = tasks.filter(function (t) { return t.status === 'PENDING'; });
    var overdue = pending.filter(function (t) { return t.dueDate < new Date().toISOString().slice(0, 10); });
    var reviewing = orders.filter(function (o) { return o.status === 'IN_REVIEW' || o.status === 'CHANGING'; });
    var latestReport = reports[0];
    var rate = latestReport ? latestReport.rate : (ax.summary ? ax.summary.onTimeRate : 100);
    var done = tasks.filter(function (t) { return t.decidedAt; })
      .sort(function (a, b) { return String(b.decidedAt).localeCompare(String(a.decidedAt)); }).slice(0, 6);

    var statusData = (ax.statusDistribution || []).map(function (d) {
      return { label: App.meta.orderStatusLabels[d.status] || d.label || d.status, count: d.count, color: orderStatusColor(d.status) };
    });
    var typeColor = { NORMAL: '#1f6feb', FORECAST: '#13b5b1', RUSH: '#e8901a' };
    var typeData = (ax.typeDistribution || []).map(function (d) {
      return { label: d.label, count: d.count, color: typeColor[d.key] || '#5a6b80' };
    });
    var roleData = (ax.roleDistribution || []).map(function (d) { return { label: d.label, count: d.count }; });
    var trend = (ax.monthlyTrend || []).map(function (d) {
      return { month: d.month, rate: d.rate, total: d.total };
    });

    var pendingRows = pending.slice(0, 8).map(function (t) {
      return row([esc(t.type), esc(t.title), esc(t.roleLabel), esc(t.dueDate),
        badge(t.status, App.meta.approvalStatusLabels),
        btn('approval-open', '处理', t.id)]);
    });
    el.innerHTML =
      '<div class="stat-grid">' +
      '<div class="stat-card accent"><div class="label">我的待办</div><div class="value">' + pending.length +
      '</div><div class="foot">逾期 ' + overdue.length + ' 条 · 已升级 ' + (st.escalated || 0) + ' 条 · 按岗位自动过滤</div></div>' +
      '<div class="stat-card"><div class="label">销售订单</div><div class="value">' + orders.length +
      '</div><div class="foot">评审/变更中 ' + reviewing.length + ' 单</div></div>' +
      '<div class="stat-card"><div class="label">发货单</div><div class="value">' + notes.length +
      '</div><div class="foot">已发货 ' + notes.filter(function (n) { return n.status === 'SHIPPED'; }).length + ' 单</div></div>' +
      '<div class="stat-card ok"><div class="label">准时交付率</div><div class="value">' + rate + '%</div>' +
      '<div class="foot">' + (ax.summary && ax.summary.reportMonth ? esc(ax.summary.reportMonth) + ' 月统计' : (latestReport ? esc(latestReport.month) + ' 月统计' : '暂无统计报告')) + '</div></div>' +
      '</div>' +
      '<div class="charts-row three">' +
      '<div class="chart-card"><div class="panel-head">订单状态分布</div><div class="panel-body">' +
      Charts.donut(statusData, { centerLabel: '订单总数' }) + '</div></div>' +
      '<div class="chart-card"><div class="panel-head">准时交付率</div><div class="panel-body">' +
      Charts.gauge(rate) + '<div class="chart-note" style="text-align:center">以最新约定交期为考核基准</div></div></div>' +
      '<div class="chart-card"><div class="panel-head">销售类型分布</div><div class="panel-body">' +
      Charts.donut(typeData, { centerLabel: '订单类型' }) + '</div></div>' +
      '</div>' +
      '<div class="two-col">' +
      '<div class="panel"><div class="panel-head">我的待办 <span class="sub">当前岗位：' +
      esc(App.meta.user.roleLabel) + '</span>' + btn('nav', '查看全部 →', null, '', ' data-tab="approvals"') + '</div>' +
      U.table(['任务类型', '标题', '指派岗位', '时限', '状态', '操作'], pendingRows) + '</div>' +
      '<div class="panel"><div class="panel-head">最新动态</div><div class="panel-body"><ul class="timeline">' +
      (done.length ? done.map(function (t) {
        return '<li><div class="at">' + A.fmt(t.decidedAt) + '</div><div>' + esc(t.operatorName) + ' ' +
          (t.status === 'APPROVED' ? '通过' : '驳回') + '：' + esc(t.title) + '</div></li>';
      }).join('') : '<li>暂无已办记录</li>') + '</ul></div></div>' +
      '</div>' +
      '<div class="charts-row">' +
      '<div class="chart-card"><div class="panel-head">岗位待办分布</div><div class="panel-body">' +
      Charts.hbar(roleData) + '<div class="chart-note">按当前系统全部待审批任务统计，跨岗位视角</div></div></div>' +
      '<div class="chart-card"><div class="panel-head">近 6 个月交付趋势</div><div class="panel-body">' +
      Charts.areaLine(trend) + '<div class="chart-note">折线为准时率，圆点为当月发货单数</div></div></div>' +
      '</div>';
  });

  /* ---------------- 我的审批 ---------------- */
  App.register('approvals', async function (el) {
    var tab = App.cache.approvalTab || 'PENDING';
    var res = await App.call('GET', '/api/approvals?status=' + tab);
    var tasks = paged(res), st = res.stats || {};
    var labels = App.meta.approvalTypeLabels || {};
    var rows = tasks.map(function (t) {
      return row([esc(t.id.slice(0, 8)), esc(labels[t.type] || t.type), esc(t.title), esc(t.roleLabel), esc(t.bizType),
        esc(t.dueDate), badge(t.status, App.meta.approvalStatusLabels) +
        (t.escalated ? ' ' + A.badge('已升级', 'danger') : ''), A.fmt(t.createdAt),
        t.status === 'PENDING' ? btn('approval-open', '处理', t.id) : esc(t.comment || '—')]);
    });
    el.innerHTML =
      '<div class="panel"><div class="tabs">' +
      [['PENDING', '待办 (' + st.pending + ')'], ['APPROVED', '已通过 (' + st.approved + ')'],
       ['REJECTED', '已驳回 (' + st.rejected + ')'], ['ALL', '全部 (' + st.total + ')']].map(function (x) {
        return '<div class="tab ' + (tab === x[0] ? 'active' : '') + '" data-act="approval-tab" data-tab="' + x[0] + '">' + x[1] + '</div>';
      }).join('') + '</div><div class="panel-body">' +
      '<div class="toolbar"><span class="form-hint">逾期 ' + (st.overdue || 0) + ' 条 · 已升级 ' +
      (st.escalated || 0) + ' 条；并行会签需全部通过才进入下一阶段。手机端可处理同一批待办：' +
      '<a href="/m.html" target="_blank">打开手机端</a></span><span class="spacer"></span>' +
      btn('nav', '超时与升级', null, '', ' data-tab="sla"') + btn('refresh', '刷新') + '</div>' +
      U.table(['编号', '类型', '标题', '岗位', '单据', '时限', '状态', '创建时间', '操作'], rows) +
      '</div></div>';
  });
  App.action('approval-tab', function (t) { App.cache.approvalTab = t.dataset.tab; App.reload(); });

  /* ---------------- 销售订单 ---------------- */
  App.register('orders', async function (el) {
    var q = App.cache.orderQuery || '';
    var status = App.cache.orderStatus || '';
    var res = await App.call('GET', '/api/orders?q=' + encodeURIComponent(q) + '&status=' + encodeURIComponent(status));
    var orders = paged(res);
    var labels = App.meta.orderStatusLabels;
    var role = App.meta.user.role;
    var canBiz = role === 'BUSINESS' || role === 'ADMIN';
    var canPlan = role === 'PLANNING' || role === 'ADMIN';
    var rows = orders.map(function (o) {
      var acts = btn('order-detail', '详情', o.id);
      var rushBlocked = o.rush && !o.rushApproved;
      if (canBiz && (o.status === 'DRAFT' || o.status === 'REJECTED')) {
        acts += rushBlocked ? '<span class="form-hint">待产能/客户确认</span>' :
          btn('order-submit', '提交', o.id);
      }
      if (canPlan && o.status === 'APPROVED') acts += btn('order-produce', '安排生产', o.id);
      if (canPlan && o.status === 'IN_PRODUCTION') acts += btn('order-instock', '完工入库', o.id);
      if (canBiz && o.status === 'IN_STOCK') acts += btn('dn-new', '开发货单', null, '', ' data-order="' + esc(o.id) + '"');
      if (canBiz && o.status === 'STOCK_DIRECT') acts += btn('nav', '查看直发单据', null, '', ' data-tab="delivery"');
      if (canBiz && o.status !== 'DRAFT' && o.status !== 'CANCELLED') acts += btn('order-change', '变更', o.id);
      if (canPlan) acts += btn('order-delay', '延期通知', o.id);
      return row([esc(o.orderNo), esc(o.customerName), esc(o.source),
        o.rush ? A.badge('急插单', 'warn') : esc(o.salesType),
        '<span class="num">' + o.quantityTotal + '</span>', App.money(o.amount), esc(o.deliveryDate),
        badge(o.status, labels), 'v' + esc(o.version || 1) + ' · ' + esc(o.reviewStage) + '/' + esc(o.totalStages), acts]);
    });
    var options = Object.keys(labels).map(function (k) {
      return '<option value="' + k + '"' + (status === k ? ' selected' : '') + '>' + esc(labels[k]) + '</option>';
    }).join('');
    el.innerHTML = '<div class="panel"><div class="panel-body">' +
      '<div class="toolbar"><button class="btn primary" data-act="order-new">+ 新建销售订单</button>' +
      '<input id="orderSearch" placeholder="搜索订单号 / 客户" value="' + esc(q) + '">' +
      '<select id="orderStatusFilter"><option value="">全部状态</option>' + options + '</select>' +
      '<button class="btn" data-act="order-search">查询</button>' +
      '<span class="spacer"></span><span class="form-hint">共 ' + orders.length + ' 单</span>' + btn('refresh', '刷新') + '</div>' +
      U.table(['订单号', '客户', '来源', '类型', '数量', '金额', '交期', '状态', '阶段', '操作'], rows) +
      '</div></div>';
  });
  App.action('order-search', function () {
    App.cache.orderQuery = App.$('orderSearch').value.trim();
    App.cache.orderStatus = App.$('orderStatusFilter').value;
    App.reload();
  });

  /* ---------------- 接单与库存 ---------------- */
  App.register('intake', async function (el) {
    var p = await App.call('GET', '/api/products');
    App.cache.products = p.items;
    el.innerHTML = '<div class="two-col">' +
      '<div class="panel"><div class="panel-head">5.1 接单预判 · 库存检查</div><div class="panel-body">' +
      '<div class="form-grid"><div class="form-row"><label>产品</label><select id="intakeProduct">' +
      U.optionList(p.items, 'id', 'name') + '</select></div>' +
      '<div class="form-row"><label>订货数量</label><input id="intakeQty" type="number" min="1" value="20"></div></div>' +
      '<div class="form-actions"><button class="btn primary" data-act="intake-check">检查库存并给出处理建议</button></div>' +
      '<div id="intakeResult" style="margin-top:12px"></div></div></div>' +
      '<div class="panel"><div class="panel-head">优化后的业务规则</div><div class="panel-body">' +
      '<ul class="timeline"><li><div class="at">库存优先</div><div>库存齐备 → 短路跳过评审链 → 财务审核 → 仓库直接出货</div></li>' +
      '<li><div class="at">财务前置</div><div>库存不足 → 财务校验价格/信用额度/收款条款 → 通过后并行评审</div></li>' +
      '<li><div class="at">并行会签</div><div>标准产品工程/计划并行；非标另加采购/品质会签，全部通过才推进</div></li>' +
      '<li><div class="at">5.1.2</div><div>国内固定客户且数量 &gt; 200PCS，由市场部业务员直接下达《销售订单》</div></li>' +
      '<li><div class="at">5.5</div><div>急插单：提前 7 工作日 + 产能校验 + 客户同意顺延 + 月度上限 ' +
      esc((App.meta.rushRules && App.meta.rushRules.monthlyLimit) || 5) + ' 单</div></li></ul>' +
      '</div></div></div>';
  });
  App.action('intake-check', async function () {
    var product = App.$('intakeProduct').value, qty = Number(App.$('intakeQty').value || 0);
    var r = await App.call('POST', '/api/orders/check-inventory', { items: [{ productId: product, quantity: qty }] });
    App.cache.intake = { productId: product, quantity: qty };
    var rows = (r.items || []).map(function (it) {
      return row([esc(it.productName), it.requested, it.stock, it.enough ? A.badge('充足', 'ok') : A.badge('不足', 'warn')]);
    });
    App.$('intakeResult').innerHTML = '<div class="panel-head">预判结果</div>' +
      U.table(['产品', '需求数量', '现有库存', '判断'], rows) +
      '<div class="form-hint" style="margin-top:8px">' + esc(r.advice) + '</div>' +
      (r.allAvailable ? '<div class="form-actions"><button class="btn primary" data-act="intake-dn">直接开具《发货单》</button></div>' :
        '<div class="form-actions"><button class="btn primary" data-act="order-new">生成《销售订单》</button></div>');
  });
  App.action('intake-dn', function () { App.actions['dn-new']({ dataset: {} }, null); });

  /* ---------------- 发货单 ---------------- */
  App.register('delivery', async function (el) {
    var res = await App.call('GET', '/api/delivery-notes');
    var notes = paged(res);
    var rows = notes.map(function (d) {
      return row([esc(d.dnNo), esc(d.customerName), esc(d.source), esc(d.contractDeliveryDate),
        badge(d.status), esc(d.shipDate || '—'), d.onTime === null || d.onTime === undefined ? '—' :
          (d.onTime ? A.badge('准时', 'ok') : A.badge('延期', 'danger')),
        btn('dn-detail', '详情', d.id)]);
    });
    el.innerHTML = '<div class="panel"><div class="panel-body"><div class="toolbar">' +
      '<button class="btn primary" data-act="dn-new">+ 开具《发货单》</button><span class="spacer"></span>' +
      '<span class="form-hint">5.1 财务审核 → 仓库出库；5.6 订单完工入库后方可发货</span>' + btn('refresh', '刷新') + '</div>' +
      U.table(['发货单号', '客户', '来源', '合同交期', '状态', '发货日期', '准时', '操作'], rows) + '</div></div>';
  });
  App.action('dn-new', async function (t) {
    var orderId = (t.dataset && t.dataset.order) || '';
    var p = await App.call('GET', '/api/products');
    var c = await App.call('GET', '/api/customers');
    var o = await App.call('GET', '/api/orders');
    App.cache.products = p.items;
    var stockOrders = paged(o).filter(function (x) { return x.status === 'IN_STOCK'; });
    var orderOptions = '<option value="">（不关联订单：库存直发）</option>' +
      U.optionList(stockOrders, 'id', 'orderNo', orderId);
    App.openModal({
      title: '开具《发货单》', wide: true,
      body: '<form id="dnForm"><div class="form-grid three">' +
        '<div class="form-row full"><label>关联已入库订单</label><select name="orderId">' + orderOptions + '</select></div>' +
        '<div class="form-row"><label>客户（直发时必填）</label><select name="customerId">' + U.optionList(c.items, 'id', 'name') + '</select></div>' +
        '<div class="form-row"><label>合同交期</label><input name="contractDeliveryDate" type="date" value="' + U.dateAfter(7) + '"></div>' +
        '<div class="form-row"><label>备注</label><input name="remark"></div>' +
        '</div><div class="panel-head" style="margin-top:10px">发货明细（不关联订单时填写）' +
        '<button type="button" class="btn sm" data-act="item-add">+ 添加明细</button></div><div id="itemRows"></div></form>',
      foot: '<button class="btn" data-act="close-modal">取消</button><button class="btn primary" data-act="dn-create">提交财务审核</button>'
    });
    App.actions['item-add']();
  });
  App.action('dn-create', async function () {
    var form = U.serialize('dnForm');
    form.items = U.collectItems();
    await App.call('POST', '/api/delivery-notes', form);
    App.closeModal(); App.toast('发货单已生成，等待财务部审核', 'ok'); App.reload();
  });
  App.action('dn-detail', async function (_, id) {
    var d = await App.call('GET', '/api/delivery-notes/' + encodeURIComponent(id));
    var rows = (d.items || []).map(function (it) {
      return row([esc(it.productName), it.quantity, App.money(it.price)]);
    });
    App.openModal({
      title: '发货单 · ' + d.dnNo, wide: true,
      body: U.kv([['单号', esc(d.dnNo)], ['状态', badge(d.status)], ['客户', esc(d.customerName)],
        ['合同交期', esc(d.contractDeliveryDate)], ['发货日期', esc(d.shipDate)],
        ['财务审核', esc(d.financeBy)], ['仓库出库', esc(d.warehouseBy)]]) +
        '<div style="height:10px"></div>' + U.table(['产品', '数量', '单价'], rows),
      foot: '<button class="btn" data-act="close-modal">关闭</button>'
    });
  });

  /* ---------------- 订单变更 ---------------- */
  App.register('changes', async function (el) {
    var res = await App.call('GET', '/api/changes');
    var list = paged(res);
    var types = {};
    (App.meta.changeTypes || []).forEach(function (x) { types[x.key] = x.label; });
    var rows = list.map(function (c) {
      return row([esc(c.changeNo), esc(c.orderNo || c.orderId), esc(types[c.type] || c.type), esc(c.reason),
        esc(c.oldDeliveryDate), esc(c.newDeliveryDate || '—'),
        esc((c.reviewers || []).join('、') || '—'),
        c.versionBefore ? 'v' + esc(c.versionBefore) + ' → v' + (Number(c.versionBefore) + 1) : '—',
        badge(c.status)]);
    });
    var typeRows = (App.meta.changeTypes || []).map(function (x) {
      return row([esc(x.label), esc(x.reviewers), esc(x.key === 'DELIVERY' ?
        '仅计划部，不重跑工程 BOM 评审' : (x.key === 'QUANTITY' ? '需财务确认金额变化' : '工程/采购重审 + 财务确认成本'))]);
    });
    el.innerHTML = '<div class="panel"><div class="panel-body"><div class="toolbar">' +
      '<button class="btn primary" data-act="change-new">+ 填写《销售订单变更及通知单》</button>' +
      '<span class="spacer"></span><span class="form-hint">5.4 按变更类型自动分派并行会签，保留版本历史，交期须满足周期表</span>' +
      btn('refresh', '刷新') + '</div>' +
      U.table(['变更单号', '订单', '类型', '原因', '原交期', '新交期', '会签岗位', '版本', '状态'], rows) +
      '</div></div><div class="panel"><div class="panel-head">变更类型与评审分派</div>' +
      U.table(['类型', '会签岗位', '规则'], typeRows) + '</div>';
  });
  App.action('change-new', async function (_, presetId) {
    var o = await App.call('GET', '/api/orders');
    var options = (App.meta.changeTypes || []).map(function (x) {
      return '<option value="' + esc(x.key) + '">' + esc(x.label) + '（' + esc(x.reviewers) + '）</option>';
    }).join('');
    App.openModal({
      title: '5.4 《销售订单变更及通知单》',
      body: '<form id="changeForm"><div class="form-grid">' +
        '<div class="form-row full"><label>关联订单 <span class="req">*</span></label><select name="orderId">' +
        U.optionList(paged(o), 'id', 'orderNo', presetId) + '</select></div>' +
        '<div class="form-row full"><label>变更类型</label><select name="type">' + options + '</select></div>' +
        '<div class="form-row"><label>新的交期（须满足生产周期表）</label><input name="newDeliveryDate" type="date" value="' + U.dateAfter(45) + '"></div>' +
        '<div class="form-row"><label>金额变动（元，可正可负）</label><input name="amountDelta" type="number" value="0"></div>' +
        '<div class="form-row full"><label>变更原因 <span class="req">*</span></label><textarea name="reason">客户提出交期变更</textarea></div>' +
        '<div class="form-row full"><label>变更内容</label><input name="details" placeholder="需同步到 ERP 的变更内容"></div>' +
        '</div><div class="form-hint">系统按类型分派并行会签；全部通过后写入订单新版本并保留变更前/后快照。</div></form>',
      foot: '<button class="btn" data-act="close-modal">取消</button><button class="btn primary" data-act="change-create">提交并行会签</button>'
    });
  });
  App.action('change-create', async function () {
    await App.call('POST', '/api/changes', U.serialize('changeForm'));
    App.closeModal(); App.toast('变更单已提交计划部重新评审', 'ok'); App.reload();
  });

  /* ---------------- 联络单 ---------------- */
  App.register('liaisons', async function (el) {
    var list = paged(await App.call('GET', '/api/liaisons'));
    var rows = list.map(function (l) {
      return row([esc(l.liaisonNo), esc(l.title), esc(l.department), esc(l.content),
        badge(l.status, LIAISON_LABELS), esc(l.approvedBy || '—'), esc(l.handleResult || '—')]);
    });
    el.innerHTML = '<div class="panel"><div class="panel-body"><div class="toolbar">' +
      '<button class="btn primary" data-act="liaison-new">+ 新建《联络单》</button><span class="spacer"></span>' +
      '<span class="form-hint">5.7 闭环：负责人审批 → 计划部处理 → 业务部确认 → 归档</span>' + btn('refresh', '刷新') + '</div>' +
      U.table(['单号', '标题', '提出部门', '需求内容', '状态', '审批人', '处理结果'], rows) + '</div></div>';
  });
  App.action('liaison-new', function () {
    App.openModal({
      title: '5.7 新建《联络单》',
      body: '<form id="liaisonForm"><div class="form-grid">' +
        '<div class="form-row full"><label>标题 <span class="req">*</span></label><input name="title"></div>' +
        '<div class="form-row full"><label>需求内容 <span class="req">*</span></label><textarea name="content"></textarea></div>' +
        '<div class="form-row"><label>提出部门</label><input name="department" value="业务部"></div>' +
        '<div class="form-row"><label>期望完成日期</label><input name="expectedDate" type="date" value="' + U.dateAfter(10) + '"></div>' +
        '</div></form>',
      foot: '<button class="btn" data-act="close-modal">取消</button><button class="btn primary" data-act="liaison-create">提交负责人审批</button>'
    });
  });
  App.action('liaison-create', async function () {
    await App.call('POST', '/api/liaisons', U.serialize('liaisonForm'));
    App.closeModal(); App.toast('联络单已提交业务部负责人审批', 'ok'); App.reload();
  });

  /* ---------------- 预测需求计划 ---------------- */
  App.register('forecasts', async function (el) {
    var list = paged(await App.call('GET', '/api/forecasts'));
    var rows = list.map(function (f) {
      var action = f.status === 'DRAFT' ? btn('forecast-submit', '提交计划部转订单', f.id) :
        (f.convertedOrderId ? btn('order-detail', '查看生成订单', f.convertedOrderId) : '—');
      return row([esc(f.forecastNo), esc(f.month), esc(f.meetingDate), esc(f.planDate),
        (f.items || []).length + ' 项', badge(f.status), esc(f.remark), action]);
    });
    el.innerHTML = '<div class="panel"><div class="panel-body"><div class="toolbar">' +
      '<button class="btn primary" data-act="forecast-new">+ 新建《预测需求计划》</button><span class="spacer"></span>' +
      '<span class="form-hint">5.8 每月 25 号会议 → 预测计划 → 计划部转预测销售订单（免评审）</span>' + btn('refresh', '刷新') + '</div>' +
      U.table(['计划单号', '月份', '会议日期', '计划投产日', '明细', '状态', '备注', '操作'], rows) + '</div></div>';
  });
  App.action('forecast-new', async function () {
    var p = await App.call('GET', '/api/products');
    App.cache.products = p.items;
    var d = new Date();
    App.openModal({
      title: '5.8 新建《预测需求计划》', wide: true,
      body: '<form id="forecastForm"><div class="form-grid three">' +
        '<div class="form-row"><label>预测月份</label><input name="month" value="' + (d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0')) + '"></div>' +
        '<div class="form-row"><label>会议日期</label><input name="meetingDate" type="date" value="' + U.dateAfter(0) + '"></div>' +
        '<div class="form-row"><label>计划投产日</label><input name="planDate" type="date" value="' + U.dateAfter(30) + '"></div>' +
        '</div><div class="panel-head" style="margin-top:10px">预测明细<button type="button" class="btn sm" data-act="item-add">+ 添加产品</button></div>' +
        '<div id="itemRows"></div><div class="form-row full" style="margin-top:8px"><label>备注</label><input name="remark"></div></form>',
      foot: '<button class="btn" data-act="close-modal">取消</button><button class="btn primary" data-act="forecast-create">保存计划</button>'
    });
    App.actions['item-add']();
  });
  App.action('forecast-create', async function () {
    var form = U.serialize('forecastForm');
    form.items = U.collectItems();
    await App.call('POST', '/api/forecasts', form);
    App.closeModal(); App.toast('预测需求计划已保存，可提交计划部转化', 'ok'); App.reload();
  });
  App.action('forecast-submit', async function (_, id) {
    await App.call('POST', '/api/forecasts/' + encodeURIComponent(id) + '/submit', {});
    App.toast('已生成计划部审批待办，通过后自动转成预测销售订单', 'ok'); App.reload();
  });

  /* ---------------- 准时交付率 ---------------- */
  App.register('reports', async function (el) {
    var list = paged(await App.call('GET', '/api/reports'));
    var rows = list.map(function (r) {
      return row([esc(r.month), r.total, r.onTimeCount, r.lateCount, r.rate + '%',
        r.beforeDeadline ? A.badge('按期', 'ok') : A.badge('超期', 'warn'),
        badge(r.status), esc(r.improveMeasures || '—'),
        btn('report-measures', '填写改善措施', r.id)]);
    });
    var latest = list[0] || {};
    var groups = latest.byGroup || [];
    var trend = list.slice().reverse().slice(-6).map(function (r) {
      return { month: r.month, rate: r.rate, total: r.total };
    });
    el.innerHTML = '<div class="panel"><div class="panel-body"><div class="toolbar">' +
      '<input id="reportMonth" placeholder="yyyy-MM，留空为上月" style="max-width:170px">' +
      '<button class="btn primary" data-act="report-gen">生成 / 查看统计</button><span class="spacer"></span>' +
      '<span class="form-hint">5.9 每月 5 号前统计上月准时交付率并提交计划部</span>' + btn('refresh', '刷新') + '</div>' +
      U.table(['月份', '发货单数', '准时', '延期', '准时率', '统计时限', '状态', '改善措施', '操作'], rows) +
      '<div class="form-hint" style="margin-top:8px">统计口径：' + esc(App.meta.deliveryRateBasis || '') + '</div>' +
      '</div></div>' +
      (list.length ? '<div class="charts-row">' +
        '<div class="chart-card"><div class="panel-head">' + esc(latest.month) + ' 月准时交付率</div><div class="panel-body">' +
        Charts.gauge(latest.rate) + '<div class="chart-note" style="text-align:center">准时 ' + (latest.onTimeCount || 0) +
        ' / ' + (latest.total || 0) + ' 单</div></div></div>' +
        '<div class="chart-card"><div class="panel-head">订单类型分组（正常 / 急插 / 预测）</div><div class="panel-body">' +
        Charts.groupedBars(groups.map(function (g) {
          return { label: g.label, total: g.total, onTime: g.onTimeCount, rate: g.rate };
        })) + '<div class="chart-note" style="text-align:center">灰底总数 · 绿底准时数</div></div></div>' +
        '</div>' +
        '<div class="chart-card"><div class="panel-head">交付率趋势（近 ' + trend.length + ' 个月）</div><div class="panel-body">' +
        Charts.areaLine(trend) + '<div class="chart-note">折线为月度准时率，圆点为当月发货单数</div></div></div>'
        : '') +
      (list.length && list[0].byGroup ? '<div class="panel"><div class="panel-head">' +
        esc(list[0].month) + ' 月分组统计（正常 / 急插 / 预测分开）</div>' +
        U.table(['订单类型', '发货单数', '准时', '准时率'], list[0].byGroup.map(function (g) {
          return row([esc(g.label), g.total, g.onTimeCount, g.rate + '%']);
        })) + '</div>' : '');
  });
  App.action('report-gen', async function () {
    var m = (App.$('reportMonth').value || '').trim();
    var r = await App.call('POST', '/api/reports/delivery-rate' + (m ? '?month=' + encodeURIComponent(m) : ''), {});
    App.toast(esc(r.month) + ' 月准时交付率 ' + r.rate + '%，已提交计划部', 'ok'); App.reload();
  });
  App.action('report-measures', function (_, id) {
    App.openModal({
      title: '计划部改善措施',
      body: '<form id="measureForm"><div class="form-row"><label>针对未准时交货笔数的分析与改善措施</label>' +
        '<textarea name="measures" placeholder="如：调整产能排期、提前锁定关键物料、缩短评审时限"></textarea></div></form>',
      foot: '<button class="btn" data-act="close-modal">取消</button><button class="btn primary" data-act="report-measures-save" data-id="' + esc(id) + '">保存</button>'
    });
  });
  App.action('report-measures-save', async function (_, id) {
    await App.call('POST', '/api/reports/' + encodeURIComponent(id) + '/measures', U.serialize('measureForm'));
    App.closeModal(); App.toast('改善措施已保存', 'ok'); App.reload();
  });

  /* ---------------- 产品生产周期表 ---------------- */
  App.register('cycles', async function (el) {
    var [t, p] = await Promise.all([App.call('GET', '/api/cycle-tables'), App.call('GET', '/api/products')]);
    var list = paged(t), products = paged(p);
    var rows = list.map(function (c) {
      var r = c.rows || {};
      var detail = Object.keys(r).map(function (k) { return esc(k) + ':' + r[k] + '天'; }).join('，');
      return row([esc(c.version), detail, badge(c.status), esc(c.approvedBy || '—'),
        esc(c.publishedBy || '—'), esc(c.publishedAt || '—'), esc(c.remark)]);
    });
    App.cache.products = products;
    el.innerHTML = '<div class="panel"><div class="panel-body"><div class="toolbar">' +
      '<button class="btn primary" data-act="cycle-new">+ 半年更新《产品生产周期表》</button><span class="spacer"></span>' +
      '<span class="form-hint">5.10 更新 → 分管领导审批 → 文控中心受控发行</span>' + btn('refresh', '刷新') + '</div>' +
      U.table(['版本', '周期明细', '状态', '审批人', '发行人', '发行时间', '备注'], rows) + '</div></div>';
  });
  App.action('cycle-new', async function () {
    var products = App.cache.products || paged(await App.call('GET', '/api/products'));
    var inputs = products.map(function (p) {
      return '<div class="form-row"><label>' + esc(p.name) + '（' + esc(p.code) + '）</label>' +
        '<input name="cycle_' + esc(p.id) + '" type="number" min="1" value="' + (p.cycleDays || 7) + '"></div>';
    }).join('');
    App.openModal({
      title: '5.10 半年更新《产品生产周期表》', wide: true,
      body: '<form id="cycleForm"><div class="form-grid">' +
        '<div class="form-row full"><label>版本号 <span class="req">*</span></label><input name="version" value="' +
        (new Date().getFullYear()) + '-H2"></div>' + inputs +
        '<div class="form-row full"><label>说明</label><input name="remark" value="与计划部半年更新，报分管领导审批"></div>' +
        '</div></form>',
      foot: '<button class="btn" data-act="close-modal">取消</button><button class="btn primary" data-act="cycle-create">提交领导审批</button>'
    });
  });
  App.action('cycle-create', async function () {
    var form = U.serialize('cycleForm');
    var rows = {};
    Object.keys(form).forEach(function (k) {
      if (k.indexOf('cycle_') === 0) rows[k.substring(6)] = Number(form[k]);
    });
    await App.call('POST', '/api/cycle-tables', { version: form.version, remark: form.remark, rows: rows });
    App.closeModal(); App.toast('已提交分管领导审批，通过后再由文控中心发行', 'ok'); App.reload();
  });

  /* ---------------- 产品与客户 ---------------- */
  App.register('products', async function (el) {
    var [p, c, log] = await Promise.all([
      App.call('GET', '/api/products'), App.call('GET', '/api/customers'), App.call('GET', '/api/inventory-logs')
    ]);
    var prodRows = paged(p).map(function (x) {
      return row([esc(x.code), esc(x.name), x.standard ? A.badge('标准', 'info') : A.badge('非标', 'warn'),
        x.bomReady ? A.badge('已制定', 'ok') : A.badge('未制定', 'danger'),
        x.cycleDays + ' 天', x.stock, App.money(x.price)]);
    });
    var custRows = paged(c).map(function (x) {
      return row([esc(x.code), esc(x.name), esc(x.type), x.fixed ? A.badge('固定客户', 'ok') : '—', esc(x.contact)]);
    });
    var logRows = paged(log).slice(0, 12).map(function (x) {
      return row([A.fmt(x.at), esc(x.productName), x.quantity,
        x.direction === 'IN' ? A.badge('入库', 'ok') : A.badge('出库', 'warn'), esc(x.operator || '—')]);
    });
    el.innerHTML = '<div class="toolbar"><button class="btn primary" data-act="product-new">+ 新增产品</button>' +
      '<button class="btn" data-act="customer-new">+ 新增客户</button><span class="spacer"></span>' + btn('refresh', '刷新') + '</div>' +
      '<div class="panel"><div class="panel-head">产品档案</div>' +
      U.table(['编码', '名称', '标准/非标', 'BOM', '生产周期', '库存', '单价'], prodRows) + '</div>' +
      '<div class="panel"><div class="panel-head">客户档案</div>' +
      U.table(['编码', '名称', '类型', '固定客户', '联系人'], custRows) + '</div>' +
      '<div class="panel"><div class="panel-head">库存流水（最近 12 条）</div>' +
      U.table(['时间', '产品', '数量', '方向', '操作人'], logRows) + '</div>';
  });
  App.action('product-new', function () {
    App.openModal({
      title: '新增产品',
      body: '<form id="productForm"><div class="form-grid">' +
        '<div class="form-row"><label>编码 <span class="req">*</span></label><input name="code"></div>' +
        '<div class="form-row"><label>名称 <span class="req">*</span></label><input name="name"></div>' +
        '<div class="form-row"><label>生产周期（天）</label><input name="cycleDays" type="number" value="7"></div>' +
        '<div class="form-row"><label>初始库存</label><input name="stock" type="number" value="0"></div>' +
        '<div class="form-row"><label>单价</label><input name="price" type="number" value="0"></div>' +
        '<div class="form-row"><label>单位</label><input name="unit" value="PCS"></div>' +
        '<label class="checkbox-row"><input type="checkbox" name="standard" checked> 标准产品</label>' +
        '<label class="checkbox-row"><input type="checkbox" name="sampleApproved" checked> 样品鉴定已通过</label>' +
        '<label class="checkbox-row"><input type="checkbox" name="trialPassed" checked> 试产已通过</label>' +
        '<label class="checkbox-row"><input type="checkbox" name="bomReady" checked> BOM 已制定</label>' +
        '</div></form>',
      foot: '<button class="btn" data-act="close-modal">取消</button><button class="btn primary" data-act="product-create">保存</button>'
    });
  });
  App.action('product-create', async function () {
    await App.call('POST', '/api/products', U.serialize('productForm',
      ['standard', 'sampleApproved', 'trialPassed', 'bomReady']));
    App.closeModal(); App.toast('产品已新增', 'ok'); App.reload();
  });
  App.action('customer-new', function () {
    App.openModal({
      title: '新增客户',
      body: '<form id="customerForm"><div class="form-grid">' +
        '<div class="form-row"><label>编码 <span class="req">*</span></label><input name="code"></div>' +
        '<div class="form-row"><label>名称 <span class="req">*</span></label><input name="name"></div>' +
        '<div class="form-row"><label>类型</label><select name="type"><option value="DOMESTIC">国内</option><option value="INTERNATIONAL">国际</option></select></div>' +
        '<div class="form-row"><label>联系人</label><input name="contact"></div>' +
        '<label class="checkbox-row"><input type="checkbox" name="fixed"> 固定客户</label>' +
        '</div></form>',
      foot: '<button class="btn" data-act="close-modal">取消</button><button class="btn primary" data-act="customer-create">保存</button>'
    });
  });
  App.action('customer-create', async function () {
    await App.call('POST', '/api/customers', U.serialize('customerForm', ['fixed']));
    App.closeModal(); App.toast('客户已新增', 'ok'); App.reload();
  });

  /* ---------------- 超时与升级（SLA） ---------------- */
  App.register('sla', async function (el) {
    var overdue = paged(await App.call('GET', '/api/sla/overdue'));
    var typeLabels = App.meta.approvalTypeLabels || {};
    var rows = overdue.map(function (t) {
      return row([esc(t.id.slice(0, 8)), esc(typeLabels[t.type] || t.type), esc(t.title),
        esc(t.roleLabel), esc(t.dueDate), t.escalated ? A.badge('已升级', 'danger') : A.badge('待升级', 'warn'),
        esc(t.escalateToLabel || '—'),
        t.status === 'PENDING' ? btn('approval-open', '处理', t.id) : '—']);
    });
    var byRole = {};
    overdue.forEach(function (t) { byRole[t.roleLabel] = (byRole[t.roleLabel] || 0) + 1; });
    var roleData = Object.keys(byRole).map(function (k) { return { label: k, count: byRole[k] }; });
    var canScan = ['ADMIN', 'PLANNING', 'LEADER'].indexOf(App.meta.user.role) >= 0;
    el.innerHTML = '<div class="stat-grid">' +
      '<div class="stat-card warn"><div class="label">逾期任务</div><div class="value">' + overdue.length + '</div>' +
      '<div class="foot">全部审批节点统一 1 个工作日内处理</div></div>' +
      '<div class="stat-card"><div class="label">升级规则</div><div class="value" style="font-size:15px">业务部 → 负责人</div>' +
      '<div class="foot">其他部门 → 分管领导</div></div>' +
      '<div class="stat-card ok"><div class="label">BOM 顺延上限</div><div class="value">' +
      esc((App.meta.slaRules && App.meta.slaRules.bomMaxDelayWorkdays) || 3) + ' 天</div>' +
      '<div class="foot">从工程部接收任务当日计时</div></div></div>' +
      (roleData.length ? '<div class="chart-card"><div class="panel-head">逾期任务岗位分布</div><div class="panel-body">' +
        Charts.hbar(roleData) + '<div class="chart-note">按责任岗位统计逾期数量，优先催办堆积岗位</div></div></div>' : '') +
      '<div class="panel"><div class="panel-body"><div class="toolbar">' +
      (canScan ? '<button class="btn primary" data-act="sla-scan">扫描并升级逾期待办</button>' :
        '<span class="form-hint">升级扫描由计划部 / 分管领导 / 管理员执行</span>') +
      '<span class="spacer"></span><span class="form-hint">' +
      esc((App.meta.slaRules && App.meta.slaRules.escalateTo) || '') +
      '；人员离职或单据搁置时由主管催办/转派</span>' + btn('refresh', '刷新') + '</div>' +
      U.table(['任务', '类型', '标题', '责任岗位', '时限', '升级状态', '升级对象', '操作'], rows) +
      '</div></div>';
  });
  App.action('sla-scan', async function () {
    var r = await App.call('POST', '/api/sla/scan', {});
    App.toast(r.message || '扫描完成', 'ok'); App.reload();
  });

  /* ---------------- 优化后的业务框架 ---------------- */
  App.register('blueprint', function (el) {
    var meta = App.meta;
    var flow = (meta.flowOverview || []).map(function (s) {
      return '<li><div class="at">流程</div><div>' + esc(s) + '</div></li>';
    }).join('');
    var improvements = (meta.improvements || []).map(function (x) {
      return row([esc(x.area), esc(x.problem), esc(x.solution)]);
    });
    var resp = (meta.responsibilities || []).map(function (x) {
      return row([esc(x.label), esc(x.duty), esc(x.creates), esc(x.reviews), esc(x.archives),
        esc(x.sla), esc(x.escalateTo)]);
    });
    var changeTypes = (meta.changeTypes || []).map(function (x) {
      return row([esc(x.label), esc(x.reviewers)]);
    });
    var mp = meta.mobilePermissions || {};
    el.innerHTML = '<div class="two-col">' +
      '<div class="panel"><div class="panel-head">优化后的主流程（11 步）</div>' +
      '<div class="panel-body"><ul class="timeline">' + flow + '</ul></div></div>' +
      '<div class="panel"><div class="panel-head">关键规则与权限</div><div class="panel-body">' +
      U.kv([['库存优先短路', '库存齐备跳过评审链，财务审核后直接出库'],
        ['财务前置', '价格 / 客户信用额度 / 收款条款，未通过退回业务'],
        ['并行会签', '标准订单工程+计划并行；非标再加采购+品质'],
        ['预测订单', 'PMC 统一录入，免签字但不免基础校验'],
        ['急插单', '提前 7 工作日 + 产能校验 + 客户同意 + 月度上限 ' +
          esc((meta.rushRules && meta.rushRules.monthlyLimit) || 5) + ' 单'],
        ['交付率口径', esc(meta.deliveryRateBasis || '')],
        ['状态机', '集中式 StateMachine 拦截非法状态跳转'],
        ['手机端可做', esc(mp.allowed || '')],
        ['手机端禁止', esc(mp.denied || '')]]) +
      '<div class="form-hint" style="margin-top:8px">' + esc(mp.note || '') + '</div></div></div></div>' +
      '<div class="panel"><div class="panel-head">岗位权责表（创建 / 审核 / 归档 / 时限 / 升级）</div>' +
      U.table(['岗位', '职责', '创建单据', '审核节点', '归档', '时限', '超时升级对象'], resp) + '</div>' +
      '<div class="two-col"><div class="panel"><div class="panel-head">变更类型与评审分派</div>' +
      U.table(['类型', '会签岗位'], changeTypes) + '</div></div>';
  });

  /* ---------------- 岗位分工 ---------------- */
  App.register('roles', async function (el) {
    var roles = (App.meta.roles || []).map(function (r) {
      return '<div class="module-card"><div class="no">' + esc(r.key) + '</div><div class="name">' +
        esc(r.label) + '</div><div class="desc">左侧导航按岗位过滤，只显示该岗位可操作的模块与待办</div></div>';
    }).join('');
    var resp = (App.meta.responsibilities || []).map(function (x) {
      return row([esc(x.label), esc(x.duty), esc(x.reviews), esc(x.archives), esc(x.sla), esc(x.escalateTo)]);
    });
    var mp = App.meta.mobilePermissions || {};
    el.innerHTML = '<div class="panel"><div class="panel-head">岗位分工（RBAC）</div><div class="panel-body">' +
      '<div class="module-grid">' + roles + '</div></div></div>' +
      '<div class="panel"><div class="panel-head">权责明细</div>' +
      U.table(['岗位', '职责', '审核节点', '归档', '时限', '升级对象'], resp) + '</div>' +
      '<div class="two-col"><div class="panel"><div class="panel-head">订单评审流程链（优化后）</div>' +
      '<div class="panel-body"><ul class="timeline">' +
      '<li><div class="at">0 前置</div><div>库存判断优先；齐备短路直发，不足才进入财务前置校验</div></li>' +
      '<li><div class="at">1 财务</div><div>价格 / 客户信用额度 / 收款条款，1 个工作日</div></li>' +
      '<li><div class="at">2 并行</div><div>标准：工程 BOM 复核 + 计划交期；非标：BOM 初稿后工程定稿 + 计划 + 采购 + 品质四部门并行会签</div></li>' +
      '<li><div class="at">3 归档</div><div>业务部确认交期、系统审核并归档纸质评审文档</div></li>' +
      '<li><div class="at">预测</div><div>PMC 统一录入，豁免签字但强制基础校验（样品/试产/BOM/周期表）</div></li>' +
      '<li><div class="at">时限</div><div>工程部从接收任务当日起 ≤10 款 1 天，最多顺延 3 天；其余节点 1 天，超时自动升级</div></li>' +
      '</ul></div></div>' +
      '<div class="panel"><div class="panel-head">移动端权限边界</div><div class="panel-body">' +
      U.kv([['允许', esc(mp.allowed || '')], ['禁止', esc(mp.denied || '')], ['说明', esc(mp.note || '')]]) +
      '<div style="height:10px"></div>' + btn('nav', '查看完整优化对照', null, '', ' data-tab="blueprint"') +
      btn('nav', '超时与升级', null, '', ' data-tab="sla"') + '</div></div></div>';
  });
})();
