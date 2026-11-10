/* PC 工作台框架：登录、按岗位导航、弹层、事件委托、审批与订单动作 */
(function () {
  'use strict';
  var A = window.API;

  var ACCOUNTS = [
    { u: 'admin', n: '系统管理员', r: 'ADMIN' },
    { u: 'business', n: '业务专员', r: 'BUSINESS' },
    { u: 'manager', n: '业务负责人', r: 'BUSINESS_MANAGER' },
    { u: 'engineer', n: '工程评审员', r: 'ENGINEERING' },
    { u: 'planner', n: '计划主管', r: 'PLANNING' },
    { u: 'finance', n: '财务审核员', r: 'FINANCE' },
    { u: 'warehouse', n: '仓库管理员', r: 'WAREHOUSE' },
    { u: 'purchase', n: '采购专员', r: 'PURCHASING' },
    { u: 'quality', n: '品质专员', r: 'QUALITY' },
    { u: 'leader', n: '分管领导', r: 'LEADER' },
    { u: 'docctl', n: '文控中心', r: 'DOC_CONTROL' }
  ];

  var App = window.App = {
    meta: null, tab: 'dashboard', views: {}, actions: {}, cache: {}, accounts: ACCOUNTS,
    register: function (name, fn) { App.views[name] = fn; },
    action: function (name, fn) { App.actions[name] = fn; },
    $: function (id) { return document.getElementById(id); },
    esc: A.esc, fmt: A.fmt, money: A.money, badge: A.badge
  };

  /* ---------- 通用工具 ---------- */
  function pad(n) { return String(n).padStart(2, '0'); }
  function dateAfter(days) {
    var d = new Date(); d.setDate(d.getDate() + days);
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
  }
  function badge(s, labels) { return A.badge((labels && labels[s]) || s || '—', A.statusKind(s)); }
  function kv(pairs) {
    return '<dl class="kv">' + pairs.map(function (p) {
      return '<dt>' + A.esc(p[0]) + '</dt><dd>' + (p[1] === undefined || p[1] === null || p[1] === '' ? '—' : p[1]) + '</dd>';
    }).join('') + '</dl>';
  }
  function table(head, rows) {
    if (!rows || !rows.length) return '<div class="empty">暂无数据</div>';
    return '<div class="table-wrap"><table class="grid"><thead><tr>' +
      head.map(function (h) { return '<th>' + A.esc(h) + '</th>'; }).join('') +
      '</tr></thead><tbody>' + rows.join('') + '</tbody></table></div>';
  }
  function serialize(formId, checks) {
    var form = App.$(formId);
    if (!form) return {};
    var out = {};
    Array.prototype.forEach.call(form.querySelectorAll('[name]'), function (el) {
      if (el.type === 'checkbox') out[el.name] = el.checked;
      else if (el.value !== '') out[el.name] = el.value.trim ? el.value.trim() : el.value;
    });
    (checks || []).forEach(function (k) { if (!(k in out)) out[k] = false; });
    return out;
  }
  function collectItems() {
    var out = [];
    Array.prototype.forEach.call(document.querySelectorAll('.item-row'), function (row) {
      var pid = row.querySelector('[name=productId]').value;
      var qty = Number(row.querySelector('[name=quantity]').value || 0);
      if (pid && qty > 0) out.push({
        productId: pid, quantity: qty,
        price: Number(row.querySelector('[name=price]').value || 0),
        specialRequirement: row.querySelector('[name=specialRequirement]').value
      });
    });
    return out;
  }
  function optionList(list, valueKey, labelKey, selected) {
    return (list || []).map(function (x) {
      var v = x[valueKey];
      return '<option value="' + A.esc(v) + '"' + (v === selected ? ' selected' : '') + '>' + A.esc(x[labelKey]) + '</option>';
    }).join('');
  }

  App.utils = {
    dateAfter: dateAfter, badge: badge, kv: kv, table: table,
    serialize: serialize, collectItems: collectItems, optionList: optionList
  };

  /* ---------- 提示 / 加载 / 弹层 ---------- */
  App.toast = function (msg, kind) {
    var box = App.$('toastBox');
    var div = document.createElement('div');
    div.className = 'toast ' + (kind || 'info');
    div.textContent = msg;
    box.appendChild(div);
    setTimeout(function () { div.remove(); }, 3600);
  };
  A.hooks.onToast = App.toast;
  A.hooks.onAuthLost = function () { showLogin(); };

  var pending = 0;
  App.call = async function (method, path, body) {
    pending++; App.$('loading').classList.add('show');
    try {
      return method === 'GET' ? await A.get(path) : await A.post(path, body || {});
    } finally {
      pending--; if (pending <= 0) App.$('loading').classList.remove('show');
    }
  };

  App.openModal = function (opt) {
    App.$('modalTitle').textContent = opt.title || '详情';
    App.$('modalBody').innerHTML = opt.body || '';
    App.$('modalFoot').innerHTML = opt.foot || '';
    App.$('modal').classList.toggle('wide', !!opt.wide);
    App.$('modal').classList.add('show');
    App.$('overlay').classList.add('show');
  };
  App.closeModal = function () {
    App.$('modal').classList.remove('show');
    App.$('overlay').classList.remove('show');
  };

  function flowSteps(tasks, totalStages) {
    if (!tasks || !tasks.length) return '<div class="form-hint">该单据无需多部门签字评审：预测销售在基础校验通过后直接生效，或库存齐备已短路转财务发货审核</div>';
    var byStage = {};
    tasks.forEach(function (t) {
      var s = t.stage || 1;
      if (!byStage[s]) byStage[s] = [];
      byStage[s].push(t);
    });
    var keys = Object.keys(byStage).map(Number).sort(function (a, b) { return a - b; });
    var html = '<div class="flow-steps">';
    keys.forEach(function (k, i) {
      var list = byStage[k];
      var allDone = list.every(function (t) { return t.status === 'APPROVED'; });
      var anyRejected = list.some(function (t) { return t.status === 'REJECTED'; });
      var cls = anyRejected ? '' : (allDone ? 'done' : 'current');
      html += '<div class="flow-step ' + cls + '"><span class="circle">' + (allDone ? '✓' : (k === 0 ? '前' : k)) + '</span>' +
        '<span>' + (k === 0 ? '前置校验' : '阶段 ' + k) + '：' +
        A.esc(list.map(function (t) { return t.roleLabel; }).join('/')) + '（并行会签）</span></div>';
      if (i < keys.length - 1) html += '<span class="flow-line"></span>';
    });
    return html + '</div>';
  }
  App.utils.flowSteps = flowSteps;

  /* ---------- 登录与外壳 ---------- */
  function showLogin() {
    App.$('loginMask').classList.remove('hide');
    App.$('appShell').style.display = 'none';
  }
  function renderQuickUsers() {
    App.$('quickUsers').innerHTML = ACCOUNTS.map(function (a) {
      return '<button class="chip" data-act="quick-login" data-u="' + a.u + '">' + A.esc(a.n) + '</button>';
    }).join('');
  }

  async function doLogin(username, password) {
    App.$('loading').classList.add('show');
    try {
      await A.login(username, password);
    } catch (e) {
      App.$('loading').classList.remove('show');
      return;
    }
    App.$('loading').classList.remove('show');
    App.$('loginMask').classList.add('hide');
    App.$('appShell').style.display = '';
    await loadMeta();
    App.go('dashboard');
  }

  async function loadMeta() {
    var m = await App.call('GET', '/api/meta');
    App.meta = m;
    App.$('topRole').textContent = m.user.roleLabel;
    App.$('logoSub').textContent = A.esc(m.appName);
    renderNav();
    renderRoleSwitch();
  }

  function allowed(roles) {
    var r = App.meta.user.role;
    return roles.indexOf('*') >= 0 || roles.indexOf(r) >= 0 || r === 'ADMIN';
  }

  function renderNav() {
    var groups = App.meta.navGroups.filter(function (g) { return allowed(g.roles); });
    App.$('topnav').innerHTML = groups.map(function (g) {
      var first = g.items[0];
      return '<a href="javascript:void(0)" data-act="nav" data-tab="' + first.tab + '" data-group="' + g.key + '">' + A.esc(g.label) + '</a>';
    }).join('');
    App.$('sidebar').innerHTML = groups.map(function (g) {
      return '<div class="nav-group"><div class="nav-group-title">' + A.esc(g.label) + '</div>' +
        g.items.map(function (it) {
          return '<div class="nav-item" data-act="nav" data-tab="' + it.tab + '" title="' + A.esc(it.desc) + '">' +
            '<span class="nav-dot"></span><span>' + A.esc(it.label) + '</span></div>';
        }).join('') + '</div>';
    }).join('');
    highlightNav();
  }

  function highlightNav() {
    Array.prototype.forEach.call(document.querySelectorAll('.nav-item'), function (el) {
      el.classList.toggle('active', el.dataset.tab === App.tab);
    });
    Array.prototype.forEach.call(document.querySelectorAll('.topnav a'), function (el) {
      var group = App.meta.navGroups.filter(function (g) {
        return g.key === el.dataset.group && g.items.some(function (i) { return i.tab === App.tab; });
      });
      el.classList.toggle('active', group.length > 0);
    });
  }

  function renderRoleSwitch() {
    App.$('roleSwitch').innerHTML = ACCOUNTS.map(function (a) {
      return '<option value="' + a.u + '"' + (a.u === App.meta.user.username ? ' selected' : '') + '>' +
        A.esc(a.n) + ' · ' + A.esc(App.meta.user.role === a.r ? '当前' : a.r) + '</option>';
    }).join('');
  }

  App.go = async function (tab) {
    if (!App.views[tab]) tab = 'dashboard';
    App.tab = tab;
    highlightNav();
    var all = [];
    App.meta.navGroups.forEach(function (g) { g.items.forEach(function (i) { all.push(i); }); });
    var item = all.filter(function (i) { return i.tab === tab; })[0] || { label: '工作台', desc: '' };
    App.$('crumb').innerHTML = '我的地盘 <b>/ ' + A.esc(item.label) + '</b>';
    App.$('pageHead').innerHTML = '<div><h2 class="page-title">' + A.esc(item.label) + '</h2>' +
      '<div class="page-desc">' + A.esc(item.desc) + '　·　当前岗位：' + A.esc(App.meta.user.roleLabel) + '</div></div>' +
      '<div><button class="btn" data-act="refresh">刷新</button></div>';
    App.$('view').innerHTML = '<div class="panel"><div class="empty">正在加载…</div></div>';
    try { await App.views[tab](App.$('view')); }
    catch (e) { App.$('view').innerHTML = '<div class="panel"><div class="empty">加载失败：' + A.esc(e.message) + '</div></div>'; }
  };
  App.reload = function () { return App.go(App.tab); };

  /* ---------- 事件委托 ---------- */
  document.addEventListener('click', async function (e) {
    var t = e.target.closest('[data-act]');
    if (!t) return;
    var act = t.dataset.act;
    if (act === 'close-modal') { App.closeModal(); return; }
    if (act === 'refresh') { App.reload(); return; }
    if (act === 'nav') { App.go(t.dataset.tab); return; }
    if (act === 'quick-login') {
      App.$('loginUser').value = t.dataset.u; App.$('loginPass').value = '123456';
      doLogin(t.dataset.u, '123456'); return;
    }
    if (App.actions[act]) {
      try { await App.actions[act](t, t.dataset.id); }
      catch (err) { if (err.message !== 'OFFLINE') App.toast(err.message, 'error'); }
    }
  });

  document.addEventListener('change', async function (e) {
    if (e.target.id === 'roleSwitch') {
      var u = e.target.value;
      A.logout();
      App.$('loginUser').value = u;
      await doLogin(u, '123456');
    }
  });

  document.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && document.activeElement && document.activeElement.id === 'loginPass') {
      App.$('loginBtn').click();
    }
  });

  window.addEventListener('offline', function () { App.$('offlineBar').classList.add('show'); });
  window.addEventListener('online', function () {
    App.$('offlineBar').classList.remove('show');
    App.toast('网络已恢复，正在刷新数据', 'ok');
    if (App.meta) App.reload();
  });

  /* ---------- 审批动作 ---------- */
  /* 不同审批节点需要回填的评审数据；均为「意见/评审数据」，不含订单核心字段 */
  function extraFields(t) {
    switch (t.type) {
      case 'ORDER_FINANCE':
        return '<div class="panel-head">财务前置校验</div><div class="form-grid">' +
          '<label class="checkbox-row"><input type="checkbox" id="ap_creditOk" checked> 客户信用额度校验通过</label>' +
          '<label class="checkbox-row"><input type="checkbox" id="ap_priceOk" checked> 订单价格校验通过</label>' +
          '<div class="form-row full"><label>收款条款</label><input id="ap_paymentTerms" placeholder="如：月结 30 天"></div></div>';
      case 'ORDER_BOM': case 'ORDER_COSIGN_ENGINEERING': case 'CHANGE_BOM':
        return '<div class="form-row"><label>BOM 用料清单说明</label><textarea id="ap_bomNote" placeholder="填写本次制定/复核的订单 BOM 用料清单"></textarea></div>';
      case 'ORDER_DELIVERY':
        return '<div class="form-grid"><div class="form-row"><label>具体生产完工日期</label><input id="ap_plannedFinishDate" type="date" value="' + dateAfter(15) + '"></div>' +
          '<div class="form-row"><label>交期回复说明</label><input id="ap_deliveryReply" placeholder="如：按评审交期完工"></div></div>';
      case 'ORDER_CONFIRM':
        return '<div class="form-row"><label>纸质评审文档归档号</label><input id="ap_archiveNo" placeholder="如：ARCH-2026-001"></div>';
      case 'ORDER_COSIGN_PURCHASE': case 'CHANGE_COSIGN_PURCHASE':
        return '<div class="form-row"><label>物料齐套评估</label><textarea id="ap_purchaseNote" placeholder="填写关键物料到料与齐套结论"></textarea></div>';
      case 'ORDER_COSIGN_QUALITY':
        return '<div class="form-row"><label>检验标准确认</label><textarea id="ap_qualityNote" placeholder="填写产品检验标准与判定要求"></textarea></div>';
      case 'RUSH_CAPACITY':
        return '<div class="form-row"><label>产能校验结论</label><textarea id="ap_capacityNote" placeholder="填写计划部产能核算结论；产能不足请直接驳回"></textarea></div>';
      case 'RUSH_CONFIRM':
        return '<div class="form-row"><label>客户书面同意顺延记录</label><textarea id="ap_customerAgreedNote" placeholder="填写客户确认方式；客户不同意请直接驳回，插单将自动取消"></textarea></div>';
      case 'LIAISON_HANDLE':
        return '<div class="form-row"><label>计划部处理结果</label><textarea id="ap_handleResult" placeholder="填写处理结果，提交后回传业务部确认结案"></textarea></div>';
      case 'CHANGE_FINANCE':
        return '<div class="form-row"><label>金额/成本变动确认</label><textarea id="ap_costNote" placeholder="填写订单金额或成本变动确认意见"></textarea></div>';
      case 'SLA_ESCALATION':
        return '<div class="form-row"><label>主管催办 / 转派意见</label><textarea id="ap_slaNote" placeholder="填写催办、转派或人员替补处理意见"></textarea></div>';
      default:
        return '';
    }
  }

  App.action('approval-open', async function (_, id) {
    var t = await App.call('GET', '/api/approvals/' + encodeURIComponent(id));
    var flow = '';
    if (t.bizType === 'ORDER') {
      try {
        var tl = await App.call('GET', '/api/timeline?bizId=' + encodeURIComponent(t.bizId));
        flow = '<div class="panel-head">流程进度</div>' + flowSteps(tl.tasks,
          (tl.order && tl.order.totalStages) || 3) +
          '<div class="timeline">' + (tl.events || []).map(function (ev) {
            return '<li><div class="at">' + A.fmt(ev.at) + '</div><div>' + A.esc(ev.text) + '</div></li>';
          }).join('') + '</div>';
      } catch (e) { flow = ''; }
    }
    var extra = extraFields(t);
    var typeLabel = (App.meta.approvalTypeLabels || {})[t.type] || t.type;
    App.openModal({
      title: '审批处理 · ' + t.roleLabel,
      wide: true,
      body: '<div class="panel-head">任务信息</div>' + kv([
        ['任务标题', A.esc(t.title)], ['任务类型', A.esc(typeLabel)], ['业务类型', A.esc(t.bizType)],
        ['指派岗位', A.esc(t.roleLabel)], ['当前状态', badge(t.status)],
        ['创建时间', A.fmt(t.createdAt)], ['处理时限', A.esc(t.dueDate) + '（工作日）'],
        ['超时升级', t.escalated ? A.badge('已升级 ' + A.esc(t.escalateToLabel || ''), 'danger') : '—']
      ]) + '<div style="height:12px"></div>' + flow +
        '<div class="panel-head">处理意见</div><div class="form-row"><textarea id="apComment" placeholder="填写审批意见"></textarea></div>' +
        '<div class="form-hint">审批人只能填写评审数据与意见；如需修改订单内容，请在 PC 端走《销售订单变更及通知单》。</div>' + extra,
      foot: '<button class="btn" data-act="close-modal">取消</button>' +
        '<button class="btn danger" data-act="approval-submit" data-id="' + A.esc(id) + '" data-approve="false">驳回</button>' +
        '<button class="btn primary" data-act="approval-submit" data-id="' + A.esc(id) + '" data-approve="true">通过</button>'
    });
  });

  App.action('approval-submit', async function (t, id) {
    var approve = t.dataset.approve === 'true';
    var comment = (App.$('apComment').value || '').trim() || (approve ? '同意' : '不同意');
    var extra = {};
    ['bomNote', 'plannedFinishDate', 'deliveryReply', 'archiveNo', 'creditOk', 'priceOk',
      'paymentTerms', 'capacityNote', 'customerAgreedNote', 'handleResult', 'costNote',
      'purchaseNote', 'qualityNote', 'slaNote', 'amountDeltaNote'].forEach(function (k) {
      var el = App.$('ap_' + k);
      if (!el) return;
      if (el.type === 'checkbox') extra[k] = el.checked;
      else if (el.value.trim()) extra[k] = el.value.trim();
    });
    await App.call('POST', '/api/approvals/' + encodeURIComponent(id) + (approve ? '/approve' : '/reject'),
      { approve: approve, comment: comment, extra: extra });
    App.closeModal();
    App.toast(approve ? '审批通过，流程已自动推进到下一阶段' : '已驳回该任务', approve ? 'ok' : 'warn');
    App.reload();
  });

  /* ---------- 订单动作 ---------- */
  App.action('item-add', function () {
    var box = App.$('itemRows');
    var products = App.cache.products || [];
    var row = document.createElement('div');
    row.className = 'item-row form-grid three';
    row.style.marginBottom = '6px';
    row.innerHTML = '<div class="form-row"><label>产品</label><select name="productId">' + optionList(products, 'id', 'name') + '</select></div>' +
      '<div class="form-row"><label>数量</label><input name="quantity" type="number" min="1" value="10"></div>' +
      '<div class="form-row"><label>单价</label><input name="price" type="number" min="0" value="0"><input name="specialRequirement" type="hidden" value=""></div>' +
      '<button type="button" class="btn sm danger" data-act="item-remove" style="align-self:end">移除</button>';
    box.appendChild(row);
  });
  App.action('item-remove', function (t) { t.closest('.item-row').remove(); });

  App.action('order-new', async function () {
    var p = await App.call('GET', '/api/products');
    var c = await App.call('GET', '/api/customers');
    App.cache.products = p.items; App.cache.customers = c.items;
    App.openModal({
      title: '新建《销售订单》 · 5.2 订单内容规范',
      wide: true,
      body: '<form id="orderForm">' +
        '<div class="form-grid three">' +
        '<div class="form-row"><label>客户 <span class="req">*</span></label><select name="customerId">' + optionList(c.items, 'id', 'name') + '</select></div>' +
        '<div class="form-row"><label>订单来源</label><select name="source"><option value="DOMESTIC">国内市场部</option><option value="INTERNATIONAL">国际业务部</option></select></div>' +
        '<div class="form-row"><label>要求交期 <span class="req">*</span></label><input name="deliveryDate" type="date" value="' + dateAfter(40) + '"></div>' +
        '</div><div style="height:10px"></div>' +
        '<div class="panel-head">订单明细 <button class="btn sm" type="button" data-act="item-add">+ 添加明细</button></div>' +
        '<div id="itemRows"></div><div style="height:10px"></div>' +
        '<div class="form-grid three">' +
        '<div class="form-row"><label>附页内容</label><input name="attachmentNote" placeholder="如有附页请详细填写"></div>' +
        '<div class="form-row"><label>待确认需求</label><input name="pendingConfirmItem" placeholder="如包装材料需客户确认"></div>' +
        '<div class="form-row"><label>初步确认日期</label><input name="pendingConfirmDate" type="date"></div>' +
        '</div><div class="form-grid three" style="margin-top:8px">' +
        '<label class="checkbox-row"><input type="checkbox" name="attachmentConfirmed"> 附页内容已确认</label>' +
        '<label class="checkbox-row"><input type="checkbox" name="engineeringConfirmed"> BOM 已由工程部确认（5.2.2）</label>' +
        '<label class="checkbox-row"><input type="checkbox" name="rush"> 急插单（须提前 7 个工作日，5.5）</label>' +
        '</div><div class="form-grid three" style="margin-top:8px">' +
        '<div class="form-row full"><label>关联原订单 ID（急插单顺延时填写）</label><input name="relatedOrderId" placeholder="可留空；客户同意顺延后系统才会调整原订单交期并发出书面通知"></div>' +
        '<div class="form-row full"><label>备注</label><input name="remark" placeholder="订单说明"></div>' +
        '</div>' +
        '<div class="form-hint" style="margin-top:8px">系统自动校验：样品鉴定与试产、BOM、交期与《产品生产周期表》、附页确认、待确认需求日期。' +
        '急插单另需「计划部产能校验 + 业务部客户同意顺延」两项前置审批，且有月度上限（当前 ' +
        ((App.meta.rushRules && App.meta.rushRules.monthlyLimit) || 5) + ' 单/月）。提交时若库存齐备自动短路直发。</div>' +
        '</form>',
      foot: '<button class="btn" data-act="close-modal">取消</button><button class="btn primary" data-act="order-create">保存并提交评审准备</button>'
    });
    App.actions['item-add']();
  });

  App.action('order-create', async function () {
    var form = serialize('orderForm', ['attachmentConfirmed', 'engineeringConfirmed', 'rush']);
    form.items = collectItems();
    if (!form.items.length) { App.toast('请至少添加一条订单明细', 'warn'); return; }
    var created = await App.call('POST', '/api/orders', form);
    App.closeModal();
    App.toast('订单 ' + created.orderNo + ' 已创建（草稿），请提交评审', 'ok');
    App.reload();
  });

  App.action('order-detail', async function (_, id) {
    var d = await App.call('GET', '/api/orders/' + encodeURIComponent(id));
    var tl = await App.call('GET', '/api/orders/' + encodeURIComponent(id) + '/timeline');
    var items = (d.items || []).map(function (it) {
      return '<tr><td>' + A.esc(it.productName) + '</td><td class="num">' + it.quantity + '</td>' +
        '<td class="num">' + App.money(it.price) + '</td><td>' + A.esc(it.specialRequirement || '—') + '</td></tr>';
    });
    App.openModal({
      title: '销售订单详情 · ' + d.orderNo, wide: true,
      body: kv([
        ['订单编号', A.esc(d.orderNo)], ['客户', A.esc(d.customerName)],
        ['状态', badge(d.status, App.meta.orderStatusLabels)],
        ['评审阶段', A.esc(d.reviewStage) + ' / ' + A.esc(d.totalStages)],
        ['订单来源', A.esc(d.source)], ['销售类型', A.esc(d.salesType)],
        ['急插单', d.rush ? A.badge('是', 'warn') : '否'], ['非标产品', d.nonStandard ? A.badge('是', 'warn') : '否'],
        ['数量合计', d.quantityTotal], ['金额', App.money(d.amount)],
        ['要求交期', A.esc(d.deliveryDate)], ['计划完工', A.esc(d.plannedFinishDate)],
        ['BOM 说明', A.esc(d.bomNote)], ['交期回复', A.esc(d.deliveryReply)],
        ['归档号', A.esc(d.archiveNo)], ['备注', A.esc(d.remark)],
        ['当前版本', 'v' + (d.version || 1)],
        ['财务前置', d.financeChecked ? A.badge('已校验', 'ok') : A.badge('待校验', 'warn')],
        ['库存优先短路', d.stockShortcut ? A.badge('已短路直发', 'info') : '—'],
        ['急插单前置', d.rush ? (d.rushApproved ? A.badge('产能+客户同意已通过', 'ok') : A.badge('待产能/客户确认', 'warn')) : '—'],
        ['优先级', A.esc(d.priority || 'NORMAL')]
      ]) + '<div style="height:12px"></div><div class="panel-head">订单明细</div>' +
        App.utils.table(['产品', '数量', '单价', '特殊要求'], items) +
        '<div style="height:12px"></div><div class="panel-head">流程进度</div>' + flowSteps(tl.tasks, d.totalStages) +
        ((d.changeHistory || []).length ? '<div class="panel-head">变更版本历史</div>' +
          App.utils.table(['版本', '变更单', '类型', '变更前交期', '变更后交期', '时间'],
            d.changeHistory.map(function (h) {
              return '<tr><td>v' + A.esc(h.version) + '</td><td>' + A.esc(h.changeNo) + '</td><td>' + A.esc(h.type) +
                '</td><td>' + A.esc((h.before || {}).deliveryDate) + '</td><td>' + A.esc((h.after || {}).deliveryDate) +
                '</td><td>' + A.fmt(h.at) + '</td></tr>';
            })) : '') +
        '<div class="panel-head">业务动态</div><ul class="timeline">' +
        (tl.events || []).map(function (ev) {
          return '<li><div class="at">' + A.fmt(ev.at) + '</div><div>' + A.esc(ev.text) + '</div></li>';
        }).join('') + '</ul>',
      foot: '<button class="btn" data-act="close-modal">关闭</button>'
    });
  });

  App.action('order-submit', async function (_, id) {
    var r = await App.call('POST', '/api/orders/' + encodeURIComponent(id) + '/submit', {});
    App.toast((r.message || '已提交') + (r.createdTasks ? '，生成 ' + r.createdTasks + ' 条岗位待办' : ''), 'ok');
    App.reload();
  });
  App.action('order-produce', async function (_, id) {
    await App.call('POST', '/api/orders/' + encodeURIComponent(id) + '/produce', {});
    App.toast('已安排生产', 'ok'); App.reload();
  });
  App.action('order-instock', async function (_, id) {
    await App.call('POST', '/api/orders/' + encodeURIComponent(id) + '/in-stock', {});
    App.toast('已完工入库，库存同步更新', 'ok'); App.reload();
  });
  App.action('order-change', function (_, id) { App.actions['change-new'](null, id); });
  App.action('order-delay', async function (_, id) {
    App.openModal({
      title: '5.4.2 《交期延期通知单》',
      body: '<form id="delayForm"><div class="form-row"><label>新的交期 <span class="req">*</span></label><input name="newDeliveryDate" type="date" value="' + dateAfter(45) + '"></div>' +
        '<div class="form-row" style="margin-top:8px"><label>延期原因</label><textarea name="reason">计划部无法按评审交期交货</textarea></div>' +
        '<div class="form-hint" style="margin-top:8px">须在交期基础上提前至少 3 个工作日提出，并由业务部与客户沟通。</div></form>',
      foot: '<button class="btn" data-act="close-modal">取消</button><button class="btn primary" data-act="order-delay-save" data-id="' + A.esc(id) + '">提交</button>'
    });
  });
  App.action('order-delay-save', async function (_, id) {
    var form = serialize('delayForm');
    await App.call('POST', '/api/orders/' + encodeURIComponent(id) + '/delay-notice', form);
    App.closeModal(); App.toast('延期通知单已发出，等待业务部沟通', 'ok'); App.reload();
  });

  /* ---------- 轮询（简易实时） ---------- */
  setInterval(function () {
    if (!App.meta || App.$('modal').classList.contains('show')) return;
    if (App.tab === 'dashboard' || App.tab === 'approvals') App.reload();
  }, 30000);

  /* ---------- 启动 ---------- */
  window.addEventListener('load', function () {
    renderQuickUsers();
    App.$('loginBtn').addEventListener('click', function () {
      doLogin(App.$('loginUser').value.trim(), App.$('loginPass').value);
    });
    App.$('logoutBtn').addEventListener('click', function () { A.logout(); showLogin(); });
    App.$('overlay').addEventListener('click', function () { App.closeModal(); });
    showLogin();
  });
})();
