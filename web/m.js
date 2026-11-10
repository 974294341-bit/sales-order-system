/* 手机端：全部审批流程的移动工作台（与 PC 共用同一批审批任务） */
(function () {
  'use strict';
  var A = window.API;
  var ACCOUNTS = [
    { u: 'admin', n: '系统管理员' }, { u: 'business', n: '业务专员' }, { u: 'manager', n: '业务负责人' },
    { u: 'engineer', n: '工程评审员' }, { u: 'planner', n: '计划主管' }, { u: 'finance', n: '财务审核员' },
    { u: 'warehouse', n: '仓库管理员' }, { u: 'purchase', n: '采购专员' }, { u: 'quality', n: '品质专员' },
    { u: 'leader', n: '分管领导' }, { u: 'docctl', n: '文控中心' }
  ];
  var S = { tab: 'todo', filter: 'PENDING', meta: null, tasks: [], orders: [] };
  function $(id) { return document.getElementById(id); }
  function esc(s) { return A.esc(s); }
  function pad(n) { return String(n).padStart(2, '0'); }
  function dateAfter(days) {
    var d = new Date(); d.setDate(d.getDate() + days);
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
  }
  function badge(s, labels) { return A.badge((labels && labels[s]) || s || '—', A.statusKind(s)); }

  function toast(msg, kind) {
    var div = document.createElement('div');
    div.className = 'toast ' + (kind || 'info');
    div.textContent = msg;
    $('toastBox').appendChild(div);
    setTimeout(function () { div.remove(); }, 3200);
  }
  A.hooks.onToast = toast;

  var pending = 0;
  async function call(method, path, body, silent) {
    if (!silent) { pending++; $('loading').classList.add('show'); }
    try { return method === 'GET' ? await A.get(path) : await A.post(path, body || {}); }
    finally { if (!silent) { pending--; if (pending <= 0) $('loading').classList.remove('show'); } }
  }

  function showLogin() {
    $('loginMask').classList.remove('hide');
    $('app').style.display = 'none';
  }
  A.hooks.onAuthLost = showLogin;

  async function login(username, password) {
    $('loading').classList.add('show');
    try { await A.login(username, password); }
    catch (e) { $('loading').classList.remove('show'); return; }
    $('loading').classList.remove('show');
    $('loginMask').classList.add('hide');
    $('app').style.display = '';
    S.meta = await call('GET', '/api/meta');
    $('roleSwitch').innerHTML = ACCOUNTS.map(function (a) {
      return '<option value="' + a.u + '"' + (a.u === S.meta.user.username ? ' selected' : '') + '>' + esc(a.n) + '</option>';
    }).join('');
    render();
  }

  function flowHtml(tasks) {
    if (!tasks || !tasks.length) return '<div class="form-hint">无需多部门签字评审：预测销售基础校验后直接生效，或库存齐备已短路转财务发货审核</div>';
    var stages = {};
    tasks.forEach(function (t) { var k = t.stage || 1; (stages[k] = stages[k] || []).push(t); });
    return Object.keys(stages).map(Number).sort(function (a, b) { return a - b; }).map(function (k) {
      var list = stages[k];
      var done = list.every(function (t) { return t.status === 'APPROVED'; });
      return '<div class="m-card" style="margin:6px 0;padding:8px 10px"><div style="display:flex;justify-content:space-between">' +
        '<b>' + (k === 0 ? '前置校验' : '阶段 ' + k) + ' · ' + esc(list[0].roleLabel) + '</b>' +
        (done ? A.badge('已完成', 'ok') : A.badge('进行中', 'warn')) +
        '</div><div class="meta">' + list.map(function (t) {
          return esc(t.roleLabel) + ' ' + (t.status === 'APPROVED' ? '✓' : t.status === 'REJECTED' ? '✕' : '待处理');
        }).join('　') + '</div></div>';
    }).join('');
  }

  /* ---------------- 待办 / 已办 ---------------- */
  async function renderTodo() {
    var res = await call('GET', '/api/approvals?status=' + S.filter, null, false);
    var list = res.items || [], st = res.stats || {};
    $('mTitle').textContent = '我的待办 · ' + S.meta.user.roleLabel;
    var chips = [['PENDING', '待办 ' + (st.pending || 0)], ['APPROVED', '已通过 ' + (st.approved || 0)],
      ['REJECTED', '已驳回 ' + (st.rejected || 0)], ['ALL', '全部']].map(function (x) {
      return '<button class="chip" data-filter="' + x[0] + '" style="' +
        (S.filter === x[0] ? 'border-color:#1f6feb;color:#1f6feb' : '') + '">' + x[1] + '</button>';
    }).join('');
    var cards = list.length ? list.map(function (t) {
      var overdue = t.status === 'PENDING' && t.dueDate < new Date().toISOString().slice(0, 10);
      return '<div class="m-card"><div class="row1"><div class="title">' + esc(t.title) + '</div>' +
        badge(t.status, S.meta.approvalStatusLabels) + '</div>' +
        '<div class="meta"><span>类型：' + esc(t.type) + '</span><span>岗位：' + esc(t.roleLabel) + '</span>' +
        '<span>时限：' + esc(t.dueDate) + (overdue ? '（逾期）' : '') + '</span><span>' + A.fmt(t.createdAt) + '</span></div>' +
        (t.status === 'PENDING' ? '<div class="actions"><button class="btn primary" data-act="open" data-id="' + esc(t.id) + '">查看并处理</button></div>' :
          '<div class="meta" style="margin-top:8px">' + esc(t.operatorName || '') + '：' + esc(t.comment || '') + '</div>') +
        '</div>';
    }).join('') : '<div class="m-card"><div class="empty">暂无' + (S.filter === 'PENDING' ? '待办' : '记录') + '</div></div>';
    $('mBody').innerHTML = '<div class="m-stat">' +
      '<div class="stat-card accent"><div class="label">待办</div><div class="value">' + (st.pending || 0) + '</div></div>' +
      '<div class="stat-card warn"><div class="label">逾期</div><div class="value">' + (st.overdue || 0) + '</div></div>' +
      '<div class="stat-card ok"><div class="label">已通过</div><div class="value">' + (st.approved || 0) + '</div></div></div>' +
      '<div class="chips" style="margin-bottom:10px">' + chips + '</div>' + cards;
  }

  /* ---------------- 单据 ---------------- */
  async function renderOrders() {
    var res = await call('GET', '/api/orders');
    var list = res.items || [];
    $('mTitle').textContent = '销售订单 · 流程跟踪';
    $('mBody').innerHTML = list.length ? list.map(function (o) {
      return '<div class="m-card"><div class="row1"><div class="title">' + esc(o.orderNo) + '　' +
        esc(o.customerName) + '</div>' + badge(o.status, S.meta.orderStatusLabels) + '</div>' +
        '<div class="meta"><span>数量 ' + o.quantityTotal + '</span><span>' + A.money(o.amount) + '</span>' +
        '<span>交期 ' + esc(o.deliveryDate) + '</span><span>阶段 ' + esc(o.reviewStage) + '/' + esc(o.totalStages) + '</span></div>' +
        '<div class="actions"><button class="btn" data-act="order" data-id="' + esc(o.id) + '">查看流程进度</button></div></div>';
    }).join('') : '<div class="m-card"><div class="empty">暂无销售订单</div></div>';
  }

  /* ---------------- 我的 ---------------- */
  function renderMe() {
    $('mTitle').textContent = '我的';
    $('mBody').innerHTML = '<div class="m-card"><div class="row1"><div class="title">' +
      esc(S.meta.user.name) + '</div>' + A.badge(S.meta.user.roleLabel, 'info') + '</div>' +
      '<div class="meta"><span>账号：' + esc(S.meta.user.username) + '</span><span>版本：' + esc(S.meta.version) + '</span></div></div>' +
      '<div class="m-card"><div class="panel-head" style="padding:0 0 8px">移动端权限边界</div>' +
      '<div class="meta">可做：' + esc((S.meta.mobilePermissions || {}).allowed || '') + '</div>' +
      '<div class="meta">禁止：' + esc((S.meta.mobilePermissions || {}).denied || '') + '</div></div>' +
      '<div class="m-card"><div class="panel-head" style="padding:0 0 8px">经营概览</div>' +
      '<div id="mAnalytics"><div class="meta">加载中…</div></div></div>' +
      '<div class="m-card"><button class="btn" data-act="logout" style="width:100%">退出登录</button></div>';
    loadMobileAnalytics();
  }
  async function loadMobileAnalytics() {
    var box = $('mAnalytics');
    if (!box) return;
    try {
      var ax = await call('GET', '/api/analytics', null, true);
      var rate = ax.summary ? ax.summary.onTimeRate : 100;
      var typeColor = { NORMAL: '#1f6feb', FORECAST: '#13b5b1', RUSH: '#e8901a' };
      var typeData = (ax.typeDistribution || []).map(function (d) {
        return { label: d.label, count: d.count, color: typeColor[d.key] || '#5a6b80' };
      });
      box.innerHTML = Charts.gauge(rate) +
        '<div class="meta" style="text-align:center;margin:4px 0 8px">准时交付率（最新统计）</div>' +
        Charts.donut(typeData, { size: 150, centerLabel: '订单类型' });
    } catch (e) { box.innerHTML = '<div class="meta">概览加载失败</div>'; }
  }

  function render() {
    Array.prototype.forEach.call(document.querySelectorAll('.m-bottom button'), function (b) {
      b.classList.toggle('active', b.dataset.mtab === S.tab);
    });
    if (S.tab === 'todo') return renderTodo();
    if (S.tab === 'orders') return renderOrders();
    return renderMe();
  }

  /* ---------------- 审批处理抽屉 ---------------- */
  /* 手机端覆盖全部审批节点；只允许回填评审数据与意见，不能修改订单核心字段 */
  function mExtraFields(t) {
    switch (t.type) {
      case 'ORDER_FINANCE':
        return '<label class="checkbox-row"><input type="checkbox" id="m_creditOk" checked> 客户信用额度校验通过</label>' +
          '<label class="checkbox-row"><input type="checkbox" id="m_priceOk" checked> 订单价格校验通过</label>' +
          '<div class="form-row"><label>收款条款</label><input id="m_paymentTerms" placeholder="如：月结 30 天"></div>';
      case 'ORDER_BOM': case 'ORDER_COSIGN_ENGINEERING': case 'CHANGE_BOM':
        return '<div class="form-row"><label>BOM 用料清单说明</label><textarea id="m_bomNote" placeholder="填写制定/复核结论"></textarea></div>';
      case 'ORDER_DELIVERY':
        return '<div class="form-row"><label>具体生产完工日期</label><input id="m_plannedFinishDate" type="date" value="' + dateAfter(15) + '"></div>' +
          '<div class="form-row"><label>交期回复</label><input id="m_deliveryReply" placeholder="按评审交期完工"></div>';
      case 'ORDER_CONFIRM':
        return '<div class="form-row"><label>纸质评审文档归档号</label><input id="m_archiveNo" placeholder="ARCH-2026-001"></div>';
      case 'ORDER_COSIGN_PURCHASE': case 'CHANGE_COSIGN_PURCHASE':
        return '<div class="form-row"><label>物料齐套评估</label><textarea id="m_purchaseNote" placeholder="关键物料到料结论"></textarea></div>';
      case 'ORDER_COSIGN_QUALITY':
        return '<div class="form-row"><label>检验标准确认</label><textarea id="m_qualityNote" placeholder="检验标准与判定要求"></textarea></div>';
      case 'RUSH_CAPACITY':
        return '<div class="form-row"><label>产能校验结论</label><textarea id="m_capacityNote" placeholder="产能不足请直接驳回"></textarea></div>';
      case 'RUSH_CONFIRM':
        return '<div class="form-row"><label>客户同意顺延记录</label><textarea id="m_customerAgreedNote" placeholder="客户不同意请直接驳回，插单自动取消"></textarea></div>';
      case 'LIAISON_HANDLE':
        return '<div class="form-row"><label>计划部处理结果</label><textarea id="m_handleResult" placeholder="提交后回传业务部确认结案"></textarea></div>';
      case 'CHANGE_FINANCE':
        return '<div class="form-row"><label>金额 / 成本变动确认</label><textarea id="m_costNote" placeholder="填写确认意见"></textarea></div>';
      case 'SLA_ESCALATION':
        return '<div class="form-row"><label>主管催办 / 转派意见</label><textarea id="m_slaNote" placeholder="填写处理意见"></textarea></div>';
      default:
        return '';
    }
  }

  async function openTask(id) {
    var t = await call('GET', '/api/approvals/' + encodeURIComponent(id));
    var flow = '';
    if (t.bizType === 'ORDER') {
      try {
        var tl = await call('GET', '/api/timeline?bizId=' + encodeURIComponent(t.bizId));
        flow = '<div class="panel-head" style="padding:10px 0 6px;border:none">流程进度</div>' + flowHtml(tl.tasks);
      } catch (e) { flow = ''; }
    }
    var extra = mExtraFields(t);
    var typeLabel = (S.meta.approvalTypeLabels || {})[t.type] || t.type;
    var payload = t.payload || {};
    var payloadHtml = Object.keys(payload).filter(function (k) { return k !== 'comment'; }).map(function (k) {
      return '<span>' + esc(k) + '：' + esc(payload[k]) + '</span>';
    }).join('');
    $('mSheetInner').innerHTML = '<h3>' + esc(t.title) + '</h3>' +
      '<div class="meta" style="color:#8a97a8;font-size:11px;margin-bottom:10px">' +
      badge(t.status, S.meta.approvalStatusLabels) + '　' + esc(typeLabel) + '　' + esc(t.roleLabel) +
      '　时限 ' + esc(t.dueDate) + (t.escalated ? '　已升级主管' : '') + '</div>' +
      (payloadHtml ? '<div class="meta" style="margin-bottom:10px">' + payloadHtml + '</div>' : '') + flow +
      '<div class="form-row"><label>审批意见</label><textarea id="mComment" placeholder="填写意见"></textarea></div>' +
      '<div class="form-hint">手机端仅可审批与填写评审意见；修改订单内容请在 PC 端提交变更单。</div>' + extra +
      '<div class="m-card" style="margin-top:12px;box-shadow:none"><div class="actions">' +
      '<button class="btn danger" data-act="decide" data-id="' + esc(id) + '" data-approve="false">驳回</button>' +
      '<button class="btn primary" data-act="decide" data-id="' + esc(id) + '" data-approve="true">通过</button></div>' +
      '<div class="actions"><button class="btn" data-act="close">关闭</button></div></div>';
    $('mSheet').classList.add('show');
  }

  async function decide(btn) {
    var id = btn.dataset.id, approve = btn.dataset.approve === 'true';
    var comment = ($('mComment').value || '').trim() || (approve ? '同意' : '不同意');
    var extra = {};
    ['bomNote', 'plannedFinishDate', 'deliveryReply', 'archiveNo', 'creditOk', 'priceOk',
      'paymentTerms', 'capacityNote', 'customerAgreedNote', 'handleResult', 'costNote',
      'purchaseNote', 'qualityNote', 'slaNote'].forEach(function (k) {
      var el = $('m_' + k);
      if (!el) return;
      if (el.type === 'checkbox') extra[k] = el.checked;
      else if (el.value.trim()) extra[k] = el.value.trim();
    });
    await call('POST', '/api/approvals/' + encodeURIComponent(id) + (approve ? '/approve' : '/reject'),
      { approve: approve, comment: comment, extra: extra });
    $('mSheet').classList.remove('show');
    toast(approve ? '审批通过，流程已自动推进' : '已驳回', approve ? 'ok' : 'warn');
    render();
  }

  async function openOrder(id) {
    var tl = await call('GET', '/api/orders/' + encodeURIComponent(id) + '/timeline');
    var o = tl.order || {};
    $('mSheetInner').innerHTML = '<h3>' + esc(o.orderNo) + ' · ' + esc(o.customerName) + '</h3>' +
      '<div class="meta" style="color:#8a97a8;font-size:11px;margin-bottom:10px">' +
      badge(o.status, S.meta.orderStatusLabels) + '　阶段 ' + esc(o.reviewStage) + '/' + esc(o.totalStages) +
      '　交期 ' + esc(o.deliveryDate) + '　v' + esc(o.version || 1) +
      (o.stockShortcut ? '　库存短路直发' : '') +
      (o.rush && !o.rushApproved ? '　待产能/客户确认' : '') + '</div>' +
      '<div class="panel-head" style="padding:8px 0;border:none">流程进度</div>' + flowHtml(tl.tasks) +
      '<div class="panel-head" style="padding:8px 0;border:none">业务动态</div><ul class="timeline">' +
      (tl.events || []).map(function (ev) {
        return '<li><div class="at">' + A.fmt(ev.at) + '</div><div>' + esc(ev.text) + '</div></li>';
      }).join('') + '</ul><div class="actions" style="margin-top:12px"><button class="btn" data-act="close">关闭</button></div>';
    $('mSheet').classList.add('show');
  }

  /* ---------------- 事件 ---------------- */
  document.addEventListener('click', async function (e) {
    var bottom = e.target.closest('.m-bottom button');
    if (bottom) { S.tab = bottom.dataset.mtab; render(); return; }
    var chip = e.target.closest('[data-filter]');
    if (chip) { S.filter = chip.dataset.filter; render(); return; }
    var t = e.target.closest('[data-act]');
    if (!t) return;
    var act = t.dataset.act;
    try {
      if (act === 'quick-login') {
        $('loginUser').value = t.dataset.u; $('loginPass').value = '123456';
        login(t.dataset.u, '123456');
      } else if (act === 'open') await openTask(t.dataset.id);
      else if (act === 'order') await openOrder(t.dataset.id);
      else if (act === 'decide') await decide(t);
      else if (act === 'close') $('mSheet').classList.remove('show');
      else if (act === 'logout') { A.logout(); showLogin(); }
    } catch (err) { if (err.message !== 'OFFLINE') toast(err.message, 'error'); }
  });

  document.addEventListener('change', async function (e) {
    if (e.target.id === 'roleSwitch') {
      A.logout();
      login(e.target.value, '123456');
    }
  });

  $('mSheet').addEventListener('click', function (e) {
    if (e.target.id === 'mSheet') $('mSheet').classList.remove('show');
  });
  window.addEventListener('offline', function () { $('offlineBar').classList.add('show'); });
  window.addEventListener('online', function () {
    $('offlineBar').classList.remove('show');
    toast('网络已恢复，正在刷新待办', 'ok');
    if (S.meta) render();
  });

  /* 轮询：30 秒静默刷新，不打断正在处理的审批抽屉 */
  setInterval(async function () {
    if (!S.meta || S.tab !== 'todo' || $('mSheet').classList.contains('show')) return;
    try { await renderTodo(); } catch (e) { /* 离线时保留现有列表 */ }
  }, 30000);

  window.addEventListener('load', function () {
    $('quickUsers').innerHTML = ACCOUNTS.map(function (a) {
      return '<button class="chip" data-act="quick-login" data-u="' + a.u + '">' + esc(a.n) + '</button>';
    }).join('');
    $('loginBtn').addEventListener('click', function () {
      login($('loginUser').value.trim(), $('loginPass').value);
    });
    showLogin();
  });
})();
