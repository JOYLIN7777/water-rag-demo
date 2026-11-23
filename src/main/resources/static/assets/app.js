/* ===================================================================
   水利知识库 · 公共外壳脚本
   负责侧栏渲染 / 当前页高亮 / 服务状态探活 / Toast
   各页面只需引入本文件并调用 initShell('当前页key')
   =================================================================== */
(function (global) {
  // 统一图标（内联 SVG，无外部依赖）
  const ICONS = {
    chat: '<path d="M21 11.5a8.4 8.4 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.4 8.4 0 0 1-3.8-.9L3 21l1.9-5.7a8.4 8.4 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.4 8.4 0 0 1 3.8-.9h.5a8.5 8.5 0 0 1 8 8v.5z"/>',
    doc: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/>',
    search: '<circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>',
    model: '<rect x="3" y="3" width="18" height="18" rx="2"/><line x1="3" y1="9" x2="21" y2="9"/><line x1="9" y1="21" x2="9" y2="9"/>',
    tree: '<rect x="9" y="2" width="6" height="4" rx="1"/><rect x="2" y="16" width="6" height="4" rx="1"/><rect x="16" y="16" width="6" height="4" rx="1"/><path d="M12 6v4M5 16v-3a3 3 0 0 1 3-3h8a3 3 0 0 1 3 3v3"/>',
    shield: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/><polyline points="9 12 11 14 15 10"/>'
  };
  const NAV = [
    { key: 'index', href: 'index.html', label: '智能问答', icon: 'chat' },
    { key: 'manage', href: 'manage.html', label: '知识库文档', icon: 'doc' },
    { key: 'search', href: 'search.html', label: '检索实验', icon: 'search' },
    { key: 'models', href: 'models.html', label: '模型配置', icon: 'model' },
    { key: 'tree', href: 'tree.html', label: '会话树', icon: 'tree' },
    { key: 'governance', href: 'governance.html', label: '企业治理', icon: 'shield' }
  ];
  const svg = name => '<svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">' + (ICONS[name] || '') + '</svg>';

  // 与首页共用同一份 localStorage 会话数据，保证从任意功能页都能回到指定会话
  function recentSessions() {
    try {
      const list = JSON.parse(global.localStorage.getItem('water-rag-sessions') || '[]');
      return Array.isArray(list)
        ? list.slice().sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0)).slice(0, 8)
        : [];
    } catch (e) { return []; }
  }
  function escapeHtml(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, c =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  function renderSidebar(active) {
    const host = document.getElementById('sidebarHost');
    if (!host) return;
    // 避免重复注入（页面切换/重复 init）
    const old = host.querySelector('.sidebar');
    if (old) old.remove();
    const items = NAV.map(n =>
      '<a class="nav-item' + (n.key === active ? ' active' : '') + '" href="' + n.href + '" title="' + n.label + '">' +
      svg(n.icon) + '<span>' + n.label + '</span></a>'
    ).join('');
    // 历史会话：与首页同源，点击带 sid 跳回首页自动定位
    const sess = recentSessions();
    const sessHtml = sess.length
      ? sess.map(s => '<a class="session-item" href="index.html?sid=' + encodeURIComponent(s.id) + '" title="' + escapeHtml(s.title || '未命名会话') + '">' +
          '<span class="session-dot"></span><span class="s-title">' + escapeHtml(s.title || '未命名会话') + '</span></a>').join('')
      : '<div style="font-size:11px;color:#5c6c8d;padding:6px 8px">暂无会话</div>';
    const aside = document.createElement('div');
    aside.innerHTML =
      '<aside class="sidebar">' +
        '<div class="brand">' +
          '<div class="brand-mark">水</div>' +
          '<div><div class="brand-title">水利知识库</div><div class="brand-sub">RAG 智能问答系统</div></div>' +
        '</div>' +
        '<div class="section-label" style="margin-top:0">工作台</div>' +
        '<nav class="nav-list">' + items + '</nav>' +
        '<a class="new-chat" href="index.html?new=1">＋ 新建会话</a>' +
        '<div class="section-label">历史会话</div>' +
        '<div class="session-list">' + sessHtml + '</div>' +
        renderUserBlock() +
        '<div class="sidebar-footer">Spring Boot 3.4 · Spring AI<br>Elasticsearch</div>' +
      '</aside>';
    host.insertBefore(aside.firstElementChild, host.firstChild);
    bindLogout();
  }

  // 侧栏底部用户卡片：显示当前登录用户，未登录时提示登录
  function renderUserBlock() {
    const auth = global.WrkAuth;
    const u = auth && auth.getUser && auth.getUser();
    const name = u ? (u.displayName || u.user) : '演示用户';
    const tenant = u ? u.tenant : 'default';
    const initial = (name || 'U').slice(0, 1).toUpperCase();
    return '<div class="user-block">' +
        '<div class="user-avatar">' + escapeHtml(initial) + '</div>' +
        '<div class="user-meta"><div class="user-name" title="' + escapeHtml(name) + '">' + escapeHtml(name) + '</div>' +
        '<div class="user-tenant">租户 ' + escapeHtml(tenant) + '</div></div>' +
        '<button class="user-logout" id="logoutBtn" title="退出登录" onclick="WrkAuth.logout()">⏻</button>' +
      '</div>';
  }

  function bindLogout() {
    // 退出按钮用内联 onclick 调 WrkAuth.logout，这里无需额外绑定；保留钩子便于后续扩展
  }

  async function checkHealth() {
    const pills = document.querySelectorAll('[data-status-pill]');
    if (!pills.length) return;
    let healthy = false;
    try {
      const j = await (await fetch('/api/search/health')).json();
      healthy = !!j.healthy;
    } catch (e) { healthy = false; }
    pills.forEach(p => {
      p.className = 'status-pill' + (healthy ? '' : ' off');
      p.innerHTML = '<span class="status-dot"></span>' + (healthy ? '服务正常' : '服务不可用');
    });
  }

  function toast(msg, type) {
    let t = document.getElementById('appToast');
    if (!t) {
      t = document.createElement('div');
      t.id = 'appToast'; t.className = 'toast';
      document.body.appendChild(t);
    }
    t.textContent = msg;
    t.className = 'toast show' + (type ? ' ' + type : '');
    clearTimeout(t._timer);
    t._timer = setTimeout(() => t.classList.remove('show'), 2600);
  }

  global.initShell = function (active) {
    renderSidebar(active);
    checkHealth();
    if (global.setInterval) setInterval(checkHealth, 30000);
  };
  global.appToast = toast;
})(window);
