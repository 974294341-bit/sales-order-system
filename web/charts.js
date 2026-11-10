/* 零依赖 SVG 图表：环形分布图 / 进度仪表盘 / 横向条形 / 面积折线。配色复用系统浅色企业风。 */
(function (global) {
  'use strict';
  var PALETTE = ['#1f6feb', '#18a058', '#e8901a', '#e0484f', '#7c5cff', '#13b5b1', '#5a6b80', '#4c8dff'];

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function polar(cx, cy, r, deg) {
    var a = (deg - 90) * Math.PI / 180;
    return [cx + r * Math.cos(a), cy + r * Math.sin(a)];
  }
  function donutArc(cx, cy, rO, rI, a0, a1) {
    var large = (a1 - a0) > 180 ? 1 : 0;
    var p1 = polar(cx, cy, rO, a0), p2 = polar(cx, cy, rO, a1);
    var p3 = polar(cx, cy, rI, a1), p4 = polar(cx, cy, rI, a0);
    return 'M' + p1[0] + ' ' + p1[1] + ' A' + rO + ' ' + rO + ' 0 ' + large + ' 1 ' + p2[0] + ' ' + p2[1] +
      ' L' + p3[0] + ' ' + p3[1] + ' A' + rI + ' ' + rI + ' 0 ' + large + ' 0 ' + p4[0] + ' ' + p4[1] + ' Z';
  }

  /** 环形分布图。data: [{label, count, color?}] */
  function donut(data, opts) {
    opts = opts || {};
    var size = opts.size || 168, cx = size / 2, cy = size / 2, rO = size / 2 - 6, rI = rO - 22;
    var total = data.reduce(function (s, d) { return s + (d.count || 0); }, 0);
    if (total <= 0) return '<div class="chart-empty">暂无数据</div>';
    var angle = 0, segs = '';
    data.forEach(function (d, i) {
      var frac = (d.count || 0) / total;
      var a0 = angle, a1 = angle + frac * 360;
      angle = a1;
      var color = d.color || PALETTE[i % PALETTE.length];
      var pct = Math.round(frac * 1000) / 10;
      segs += '<path d="' + donutArc(cx, cy, rO, rI, a0, a1 > 359.9 ? 359.9 : a1) +
        '" fill="' + color + '" stroke="#fff" stroke-width="1.5"><title>' + esc(d.label) + '：' +
        d.count + '（' + pct + '%）</title></path>';
    });
    var legend = data.map(function (d, i) {
      var color = d.color || PALETTE[i % PALETTE.length];
      return '<div class="lg-item"><span class="lg-dot" style="background:' + color + '"></span>' +
        '<span class="lg-label">' + esc(d.label) + '</span><span class="lg-val">' + d.count + '</span></div>';
    }).join('');
    var center = opts.centerLabel
      ? '<text x="' + cx + '" y="' + (cy - 4) + '" text-anchor="middle" style="font-size:11px;fill:#8a97a8">' + esc(opts.centerLabel) + '</text>' +
        '<text x="' + cx + '" y="' + (cy + 16) + '" text-anchor="middle" style="font-size:22px;font-weight:700;fill:#263445">' + total + '</text>'
      : '<text x="' + cx + '" y="' + (cy + 6) + '" text-anchor="middle" style="font-size:22px;font-weight:700;fill:#263445">' + total + '</text>';
    return '<div class="chart-flex"><svg class="chart-donut" viewBox="0 0 ' + size + ' ' + size +
      '" width="' + size + '" height="' + size + '">' + segs + center + '</svg>' +
      '<div class="chart-legend">' + legend + '</div></div>';
  }

  /** 进度仪表盘 0-100。value: 数值；opts: {suffix, label} */
  function gauge(value, opts) {
    opts = opts || {};
    var size = opts.size || 168, cx = size / 2, cy = size / 2, r = size / 2 - 14;
    var startA = 135, endA = 405, span = endA - startA;
    var v = Math.max(0, Math.min(100, Number(value) || 0));
    var valA = startA + span * (v / 100);
    function arc(a0, a1) {
      var p1 = polar(cx, cy, r, a1), p2 = polar(cx, cy, r, a0);
      var large = (a1 - a0) <= 180 ? 0 : 1;
      return 'M ' + p1[0] + ' ' + p1[1] + ' A ' + r + ' ' + r + ' 0 ' + large + ' 0 ' + p2[0] + ' ' + p2[1];
    }
    var color = v >= 95 ? '#18a058' : v >= 85 ? '#e8901a' : '#e0484f';
    var title = opts.label || '准时交付率';
    return '<div class="chart-gauge"><svg viewBox="0 0 ' + size + ' ' + size + '" width="' + size + '" height="' + size + '">' +
      '<path d="' + arc(startA, endA) + '" fill="none" stroke="#eef2f7" stroke-width="12" stroke-linecap="round"/>' +
      '<path d="' + arc(startA, valA) + '" fill="none" stroke="' + color + '" stroke-width="12" stroke-linecap="round"/>' +
      '<text x="' + cx + '" y="' + (cy - 2) + '" text-anchor="middle" style="font-size:30px;font-weight:700;fill:' + color + '">' +
      v + (opts.suffix || '%') + '</text>' +
      '<text x="' + cx + '" y="' + (cy + 20) + '" text-anchor="middle" style="font-size:12px;fill:#8a97a8">' + esc(title) + '</text>' +
      '</svg></div>';
  }

  /** 横向条形。data: [{label, count, color?}]；opts: {max} */
  function hbar(data, opts) {
    opts = opts || {};
    var max = opts.max || data.reduce(function (m, d) { return Math.max(m, d.count || 0); }, 0);
    if (max <= 0) return '<div class="chart-empty">暂无数据</div>';
    var rows = data.map(function (d, i) {
      var color = d.color || PALETTE[i % PALETTE.length];
      var w = Math.round((d.count / max) * 100);
      return '<div class="hbar-row"><span class="hbar-label">' + esc(d.label) + '</span>' +
        '<span class="hbar-track"><span class="hbar-fill" style="width:' + w + '%;background:' + color + '"></span></span>' +
        '<span class="hbar-val">' + d.count + '</span></div>';
    }).join('');
    return '<div class="hbar">' + rows + '</div>';
  }

  /** 面积折线（趋势）。data: [{month, rate, total}] */
  function areaLine(data, opts) {
    opts = opts || {};
    if (!data || !data.length) return '<div class="chart-empty">暂无趋势数据</div>';
    var w = 560, h = 220, padL = 36, padR = 14, padT = 16, padB = 28;
    var iw = w - padL - padR, ih = h - padT - padB;
    var n = data.length;
    var xs = function (i) { return padL + (n === 1 ? iw / 2 : iw * i / (n - 1)); };
    var ys = function (v) { return padT + ih * (1 - v / 100); };
    var points = data.map(function (d, i) {
      return [xs(i), ys(Number(d.rate) || 0), d];
    });
    var line = points.map(function (p) { return p[0] + ',' + p[1]; }).join(' ');
    var area = 'M' + points[0][0] + ',' + (padT + ih) + ' L' + line.replace(/ /g, ' L') +
      ' L' + points[n - 1][0] + ',' + (padT + ih) + ' Z';
    var grid = '';
    [0, 25, 50, 75, 100].forEach(function (g) {
      var y = ys(g);
      grid += '<line x1="' + padL + '" y1="' + y + '" x2="' + (w - padR) + '" y2="' + y + '" stroke="#eef2f7" stroke-width="1"/>' +
        '<text x="' + (padL - 6) + '" y="' + (y + 4) + '" text-anchor="end" style="font-size:10px;fill:#8a97a8">' + g + '%</text>';
    });
    var xlabels = data.map(function (d, i) {
      return '<text x="' + xs(i) + '" y="' + (h - 8) + '" text-anchor="middle" style="font-size:10px;fill:#8a97a8">' +
        esc(String(d.month).slice(5) + '月') + '</text>';
    }).join('');
    var dots = points.map(function (p) {
      var tip = esc(String(p[2].month).slice(5)) + '月 准时率 ' + p[2].rate +
        '% 发货 ' + p[2].total + ' 单';
      return '<circle cx="' + p[0] + '" cy="' + p[1] + '" r="3.5" fill="#1f6feb"><title>' +
        tip + '</title></circle>';
    }).join('');
    return '<svg class="chart-area" viewBox="0 0 ' + w + ' ' + h + '" width="100%" preserveAspectRatio="xMidYMid meet">' +
      grid + '<path d="' + area + '" fill="rgba(31,111,235,.12)"/>' +
      '<polyline points="' + line + '" fill="none" stroke="#1f6feb" stroke-width="2.5" stroke-linejoin="round"/>' +
      dots + xlabels + '</svg>';
  }

  /** 分组柱状（准时率分组对比）。groups: [{label, total, onTime, rate}] */
  function groupedBars(groups, opts) {
    if (!groups || !groups.length) return '<div class="chart-empty">暂无数据</div>';
    var w = 460, h = 200, padB = 26, padT = 14, padL = 10, padR = 10;
    var iw = w - padL - padR, ih = h - padT - padB;
    var n = groups.length, slot = iw / n, bw = Math.min(54, slot * 0.5);
    var max = groups.reduce(function (m, g) { return Math.max(m, g.total || 0); }, 0) || 1;
    var y = function (v) { return padT + ih * (1 - v / max); };
    var bars = groups.map(function (g, i) {
      var cxp = padL + slot * i + slot / 2;
      var x1 = cxp - bw / 2;
      var totalH = ih * (g.total / max);
      var onH = ih * ((g.onTime || 0) / max);
      return '<rect x="' + x1 + '" y="' + (padT + ih - totalH) + '" width="' + bw + '" height="' + totalH +
        '" rx="3" fill="#cdd7e4"/>' +
        '<rect x="' + x1 + '" y="' + (padT + ih - onH) + '" width="' + bw + '" height="' + onH +
        '" rx="3" fill="#18a058"/>' +
        '<text x="' + cxp + '" y="' + (padT + ih + 16) + '" text-anchor="middle" style="font-size:10px;fill:#5a6b80">' +
        esc(g.label) + '</text>' +
        '<text x="' + cxp + '" y="' + (padT + ih - totalH - 4) + '" text-anchor="middle" style="font-size:10px;fill:#263445">' +
        (g.rate != null ? g.rate + '%' : '') + '</text>';
    }).join('');
    return '<svg class="chart-bars" viewBox="0 0 ' + w + ' ' + h + '" width="100%" preserveAspectRatio="xMidYMid meet">' +
      bars + '</svg>';
  }

  global.Charts = {
    palette: PALETTE,
    donut: donut, gauge: gauge, hbar: hbar, areaLine: areaLine, groupedBars: groupedBars
  };
})(window);
