/* 统一 API 客户端：令牌只存内存；401 自动刷新重试；4xx 不重试、5xx 最多 3 次；失败映射友好文案。 */
(function (global) {
  'use strict';

  var BASE = (global.APP_API_BASE || '').replace(/\/$/, '');
  var state = { accessToken: null, refreshToken: null, user: null, expiresAt: 0 };
  var hooks = { onAuthLost: null, onToast: null };

  var MESSAGES = {
    BAD_REQUEST: '请求格式有误，请检查输入',
    VALIDATION_FAILED: '填写内容不符合业务规则',
    UNAUTHORIZED: '登录状态失效，请重新登录',
    TOKEN_EXPIRED: '登录已过期，正在自动刷新',
    FORBIDDEN: '当前岗位无权执行此操作',
    NOT_FOUND: '数据不存在或已被删除',
    CONFLICT: '当前状态不允许此操作',
    INTERNAL_ERROR: '服务暂时不可用，请稍后再试'
  };

  function toast(msg, kind) { if (hooks.onToast) hooks.onToast(msg, kind || 'info'); }
  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

  function friendly(err) {
    if (!err) return MESSAGES.INTERNAL_ERROR;
    if (err.code === 'VALIDATION_FAILED' || err.code === 'CONFLICT') return err.message || MESSAGES[err.code];
    return MESSAGES[err.code] || err.message || MESSAGES.INTERNAL_ERROR;
  }

  function clear() {
    state.accessToken = null; state.refreshToken = null; state.user = null; state.expiresAt = 0;
  }

  async function request(path, options) {
    var opt = options || {};
    var method = opt.method || 'GET';
    var body = opt.body || null;
    var retry = opt.retry || 0;
    var refreshed = opt.refreshed || false;

    if (global.navigator && navigator.onLine === false) {
      toast('当前处于离线状态，请检查网络后重试', 'warn');
      throw new Error('OFFLINE');
    }
    var headers = { 'Content-Type': 'application/json' };
    if (global.APP_CLIENT) headers['X-Client'] = global.APP_CLIENT;
    if (state.accessToken) headers['Authorization'] = 'Bearer ' + state.accessToken;

    var res;
    try {
      res = await fetch(BASE + path, {
        method: method,
        headers: headers,
        body: body === null ? undefined : JSON.stringify(body)
      });
    } catch (e) {
      toast('网络连接失败，已切换为离线提示', 'warn');
      throw new Error('OFFLINE');
    }

    if (res.status >= 500 && retry < 3) {
      await sleep(250 * (retry + 1));
      return request(path, { method: method, body: body, retry: retry + 1, refreshed: refreshed });
    }

    var data = {};
    try { data = await res.json(); } catch (e) { data = {}; }
    if (!res.ok) {
      var err = data.error || {};
      if (res.status === 401 && err.code === 'TOKEN_EXPIRED' && state.refreshToken && !refreshed) {
        var ok = await refresh();
        if (ok) return request(path, { method: method, body: body, retry: 0, refreshed: true });
      }
      if (res.status === 401) {
        clear();
        if (hooks.onAuthLost) hooks.onAuthLost();
      }
      var message = friendly(err);
      toast(message, 'error');
      var error = new Error(message);
      error.status = res.status;
      error.code = err.code;
      throw error;
    }
    return data;
  }

  async function login(username, password) {
    var data = await request('/api/auth/login', { method: 'POST', body: { username: username, password: password } });
    state.accessToken = data.accessToken;
    state.refreshToken = data.refreshToken;
    state.user = data.user;
    state.expiresAt = Date.now() + data.expiresIn * 1000;
    return data.user;
  }

  async function refresh() {
    if (!state.refreshToken) return false;
    try {
      var refreshHeaders = { 'Content-Type': 'application/json' };
      if (global.APP_CLIENT) refreshHeaders['X-Client'] = global.APP_CLIENT;
      var res = await fetch(BASE + '/api/auth/refresh', {
        method: 'POST',
        headers: refreshHeaders,
        body: JSON.stringify({ refreshToken: state.refreshToken })
      });
      if (!res.ok) return false;
      var data = await res.json();
      state.accessToken = data.accessToken;
      state.refreshToken = data.refreshToken || state.refreshToken;
      state.expiresAt = Date.now() + data.expiresIn * 1000;
      state.user = data.user;
      return true;
    } catch (e) { return false; }
  }

  function esc(v) {
    if (v === null || v === undefined) return '';
    return String(v).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function fmt(v) {
    if (!v) return '—';
    return String(v).replace('T', ' ').slice(0, 16);
  }

  function money(v) {
    var n = Number(v) || 0;
    return '¥' + n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }

  function badge(text, kind) {
    return '<span class="badge ' + (kind || 'default') + '">' + esc(text) + '</span>';
  }

  function statusKind(status) {
    var map = {
      PENDING: 'warn', IN_REVIEW: 'info', APPROVED: 'ok', REJECTED: 'danger', CANCELLED: 'muted',
      DRAFT: 'muted', CREATED: 'info', FINANCE_APPROVED: 'info', SHIPPED: 'ok',
      IN_PRODUCTION: 'info', IN_STOCK: 'ok', CHANGING: 'warn', PUBLISHED: 'ok', CONVERTED: 'ok',
      APPLIED: 'ok', NOTIFIED: 'info', COMMUNICATED: 'ok', SUBMITTED: 'ok',
      STOCK_DIRECT: 'info', WAIT_PLANNING: 'warn', WAIT_CONFIRM: 'info', CLOSED: 'muted',
      PENDING_CONVERT: 'warn', DELIVERY_FINANCE: 'info'
    };
    return map[status] || 'default';
  }

  global.API = {
    state: state,
    hooks: hooks,
    request: request,
    get: function (p) { return request(p, { method: 'GET' }); },
    post: function (p, b) { return request(p, { method: 'POST', body: b || {} }); },
    login: login,
    logout: function () { clear(); if (hooks.onAuthLost) hooks.onAuthLost(); },
    esc: esc, fmt: fmt, money: money, badge: badge, statusKind: statusKind, friendly: friendly,
    toast: toast
  };
})(window);
