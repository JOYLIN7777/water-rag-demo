/* ===================================================================
   水利知识库 · 公共外壳组件（全站唯一侧栏渲染源）
   所有页面共用：侧栏 DOM / 会话列表 / 上传折叠区 / 文档清单 / 用户卡片 /
   身份回显 / 服务探活 / Toast / 导航权限过滤。
   首页问答逻辑通过 Shell.onSessionChange 钩子感知会话切换。
   =================================================================== */
(function (global) {
  // ---------- 图标 ----------
  const ICONS = {
    chat: '<path d="M21 11.5a8.4 8.4 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.4 8.4 0 0 1-3.8-.9L3 21l1.9-5.7a8.4 8.4 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.4 8.4 0 0 1 3.8-.9h.5a8.5 8.5 0 0 1 8 8v.5z"/>',
    doc: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/>',
    search: '<circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>',
    model: '<rect x="3" y="3" width="18" height="18" rx="2"/><line x1="3" y1="9" x2="21" y2="9"/><line x1="9" y1="21" x2="9" y2="9"/>',
    tree: '<rect x="9" y="2" width="6" height="4" rx="1"/><rect x="2" y="16" width="6" height="4" rx="1"/><rect x="16" y="16" width="6" height="4" rx="1"/><path d="M12 6v4M5 16v-3a3 3 0 0 1 3-3h8a3 3 0 0 1 3 3v3"/>',
    shield: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/><polyline points="9 12 11 14 15 10"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
    settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .34 1.87l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.7 1.7 0 0 0-1.87-.34 1.7 1.7 0 0 0-1.03 1.56V21a2 2 0 1 1-4 0v-.09A1.7 1.7 0 0 0 9 19.4a1.7 1.7 0 0 0-1.87.34l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.7 1.7 0 0 0 4.6 15a1.7 1.7 0 0 0-1.56-1.03H3a2 2 0 1 1 0-4h.09A1.7 1.7 0 0 0 4.6 9a1.7 1.7 0 0 0-.34-1.87l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1.03-1.56V3a2 2 0 1 1 4 0v.09A1.7 1.7 0 0 0 15 4.6a1.7 1.7 0 0 0 1.87-.34l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.7 1.7 0 0 0 19.4 9v0a1.7 1.7 0 0 0 1.56 1.03H21a2 2 0 1 1 0 4h-.09A1.7 1.7 0 0 0 19.4 15z"/>'
  };
  // 工作台主导航只保留高频两项；其余功能统一收进「设置」页，避免侧栏冗长
  // perm = 菜单权限码：权限页没勾的账号，侧栏不渲染该项（页面内还有第二道守卫）
  const NAV = [
    { key: 'index', href: 'index.html', label: '智能问答', icon: 'chat', perm: 'chat:use', groups: [] },
    { key: 'manage', href: 'manage.html', label: '知识库文档', icon: 'doc', perm: 'doc:read', groups: [] }
  ];
  // 设置页内的功能入口（含功能权限控制）
  // 设置只放面向普通用户的功能；模型配置、权限审计等管理动作统一收进管理后台，
  // 避免管理员在问答侧和管理后台之间来回跳。
  const SETTINGS = [
    { key: 'search', href: 'search.html', label: '检索对比', icon: 'search', perm: 'search:use',
      desc: '对比关键词检索、语义检索和融合检索的结果，帮助找到更准的答案', groups: [] },
    { key: 'tree', href: 'tree.html', label: '会话结构', icon: 'tree', perm: 'tree:use',
      desc: '查看当前会话的消息树与命名分支，可切回任意历史节点', groups: [] }
  ];
  global.ShellSettings = SETTINGS;

  // 后台侧栏：治理/审计类页面虽不在 admin.html 内，但属于管理域，
  // 统一显示后台导航而不是问答侧栏（审计员登录后看到的应该是后台的一套入口）。
  // href 指向 admin.html 的对应面板；带 panel 参数由后台页面自己激活。
  const ADMIN_NAV = [
    { key: 'users', label: '用户管理', icon: '◈', href: 'users.html', perm: 'user:read' },
    { key: 'org', label: '组织架构', icon: '⌘', href: 'org.html', perm: 'org:read' },
    { key: 'roles', label: '角色定义', icon: '❖', href: 'roles.html', perm: 'role:read' },
    { key: 'perms', label: '菜单权限', icon: '☷', href: 'perms.html', perm: 'perm:read' },
    { key: 'docs', label: '文档管理', icon: '▤', href: 'docs.html', perm: 'admin:docs' },
    { key: 'models', label: '模型设置', icon: '◇', href: 'models.html', perm: 'model:read' },
    { key: 'usage', label: '用量统计', icon: '◷', href: 'usage.html', perm: 'usage:read' },
    // 日志类入口直接按菜单权限目录的路由进入各自页面（管理员与审计员看到同一页面）
    { key: 'loginlogs', label: '登录日志', icon: '⎗', href: 'loginlogs.html', perm: 'loginlog:read' },
    { key: 'audit', label: '操作日志', icon: '≡', href: 'audit.html', perm: 'audit:read' }
  ];
  const ADMIN_GROUPS = [
    { label: '权限配置目录', keys: ['users', 'org', 'roles', 'perms'] },
    { label: '业务管理', keys: ['docs', 'models', 'usage'] },
    { label: '日志审计', keys: ['loginlogs', 'audit'] }
  ];

  // 内部标识转中文显示名，避免把 admin / water-dept / default 直接暴露给用户
  const GROUP_LABELS = {
    admin: '系统管理员', 'water-dept': '水利业务组', analyst: '水利分析员',
    viewer: '只读用户', user: '普通用户', ops: '运维人员'
  };
  // 组织名（原"空间"）：不再写死成 default→默认空间。服务端 /api/orgs 下发，
  // 管理员在后台改了组织名，问答页、个人中心的显示同步跟着变。
  let TENANT_LABELS = { default: '顶层组织' };
  const groupLabel = g => GROUP_LABELS[g] || g;
  const tenantLabel = t => TENANT_LABELS[t] || t || '顶层组织';
  async function loadOrgLabels() {
    try {
      const r = await fetch('/api/orgs');
      if (!r.ok) return false;
      const list = await r.json();
      if (!Array.isArray(list) || !list.length) return false;
      const next = {};
      list.forEach(o => { if (o && o.key) next[o.key] = o.name || o.key; });
      TENANT_LABELS = next;
      return true;
    } catch (e) { return false; }
  }

  const svg = name => '<svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">' + (ICONS[name] || '') + '</svg>';
  const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  // ---------- 自动生成头像 ----------
  // 不接外部头像服务：同一个账号名永远得到同一组色相，换个浏览器打开也一致。
  // 取首字上屏——中文取第一个汉字，英文取首字母大写，比灰底问号可辨认得多。
  // 头像配色不用全色环：随机 hue 会冒出荧光绿/洋红，在水系蓝的后台里很突兀。
  // 这里只用 8 组「蓝—青—靛」邻近色，既有区分度，又始终和主色调同一家族。
  const AV_PALETTE = [
    ['#4b7bec', '#2f56d0'], ['#3aa8e0', '#1d76c9'], ['#38b2ac', '#237f8f'],
    ['#5b8def', '#3355b8'], ['#48a9d8', '#2268a8'], ['#6a7ee8', '#3f46b5'],
    ['#2fa8c9', '#1b6f96'], ['#4fa3d1', '#2a5fa0']
  ];
  function hashHue(s) {
    let h = 0;
    for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
    return h;
  }
  function avatar(name, size) {
    const n = String(name == null || name === '' ? '?' : name).trim();
    const ch = n.slice(0, 1).toUpperCase();
    const pair = AV_PALETTE[hashHue(n) % AV_PALETTE.length];
    return '<span class="av av-' + (size || 'md') + '" style="background:linear-gradient(135deg,' + pair[0] + ',' + pair[1] + ')">' +
      esc(ch) + '</span>';
  }


  // ---------- 会话清单：服务端为准，localStorage 做首屏缓存 ----------
  // 以前清单只存浏览器：换电脑、清缓存、换浏览器就全没了，而对话内容其实还在 Redis。
  // 现在清单落 PostgreSQL（/api/chat/sessions），本地缓存只负责"打开就渲染、不等网络"。
  function sessionKey() {
    const a = global.WrkAuth;
    return 'water-rag-sessions:' + (a && a.scope ? a.scope() : 'demo');
  }
  /**
   * 本地会话清单。
   *
   * <p>只返回"已经提过问"的会话（有标题）。新建会话只是个占位：用户点开一个空会话
   * 什么也没问就离开，不该在历史里留下一条「会话 179542」——历史会话是记录，
   * 不是输入框的草稿本。首次提问由 Ask 页调 pushSessionRemote 补上标题后才出现。</p>
   */
  function loadSessions() {
    try {
      const all = JSON.parse(global.localStorage.getItem(sessionKey()) || '[]');
      return (Array.isArray(all) ? all : []).filter(function (s) {
        return s && s.id && String(s.title || '').trim();
      });
    } catch (e) { return []; }
  }
  function saveSessions(list) {
    global.localStorage.setItem(sessionKey(), JSON.stringify(list));
  }
  function pushSessionRemote(id, title) {
    try {
      return fetch('/api/chat/sessions', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: id, title: title || '' })
      }).catch(function () {});
    } catch (e) { return Promise.resolve(); }
  }
  function patchSessionRemote(id, title) {
    try {
      return fetch('/api/chat/sessions/' + encodeURIComponent(id), {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: title || '' })
      }).catch(function () {});
    } catch (e) { return Promise.resolve(); }
  }
  function deleteSessionRemote(id) {
    try {
      return fetch('/api/chat/sessions/' + encodeURIComponent(id), { method: 'DELETE' }).catch(function () {});
    } catch (e) { return Promise.resolve(); }
  }
  let syncInFlight = null;
  /**
   * 拉服务端清单并对齐本地。
   *
   * <p>对齐规则（服务端是真源，本地条目带 synced 标记区分"来历"）：
   * <ul>
   *   <li><b>synced 条目</b>：曾在服务端清单里出现过。此后它在服务端消失就等于
   *       被删除——本地直接清掉，<b>任何情况下不复活</b>。这是"删掉的会话又回来/
   *       列表莫名变多"的根治：旧版在服务端清单为空时把本地缓存整体回灌，
   *       账号换组织、换库清库都会触发，删掉的历史全部诈尸；</li>
   *   <li><b>未同步条目</b>（没出现过）：两种来历——老版本留下的存量，或刚提问、
   *       POST 还没落库的竞态。存量在服务端为空时整体迁移上去；竞态条目豁免
   *       60 秒，60 秒后仍未出现在服务端就清掉；</li>
   *   <li>服务端不可达：本地缓存原样保留，离线也能看历史。</li>
   * </ul></p>
   */
  function syncSessions() {
    // 并发去重：initShell 与页面 boot 都会调一次，共用同一个请求
    if (syncInFlight) return syncInFlight;
    syncInFlight = fetch('/api/chat/sessions').then(function (r) { return r.ok ? r.json() : null; }).then(function (rows) {
      if (!Array.isArray(rows)) return;
      const local = loadSessions();
      const serverIds = {};
      rows.forEach(function (r) { serverIds[r.id] = 1; });
      // 给"服务端见过的"条目盖 synced 章：从此它的生死由服务端说了算
      local.forEach(function (s) { if (!s.synced && serverIds[s.id]) s.synced = 1; });

      if (!rows.length) {
        // 服务端清单为空：只有"从未同步过"的条目才值得迁移上去（老版本存量升级）。
        // synced 条目不回灌——服务端空就是被删光了，回灌等于诈尸
        const migrate = local.filter(function (s) { return !s.synced; });
        migrate.forEach(function (s) { pushSessionRemote(s.id, s.title || ''); });
        saveSessions(migrate);
        renderSessions();
        if (typeof sessionsSyncedHandler === 'function') sessionsSyncedHandler();
        return;
      }

      // 服务端非空：以服务端为骨架。本地独有条目只豁免"60 秒内且从未同步过"的
      // 竞态窗口（刚提问 POST 还在路上）；synced 条目不在服务端 = 已被删除
      const freshCut = Date.now() - 60000;
      const orphans = local.filter(function (s) {
        return !serverIds[s.id] && !s.synced && (s.updatedAt || 0) >= freshCut;
      });
      const merged = rows.map(function (r) {
        return { id: r.id, title: r.title || '', updatedAt: r.updatedAt || 0, synced: 1 };
      }).concat(orphans);
      saveSessions(merged);
      renderSessions();
      if (typeof sessionsSyncedHandler === 'function') sessionsSyncedHandler();
    }).catch(function () { /* 离线时静默走本地缓存 */ }).then(function () {
      syncInFlight = null;
    });
    return syncInFlight;
  }
  function fmtTime(ts) {
    if (!ts) return '';
    const d = new Date(ts), now = new Date();
    if (isNaN(d.getTime())) return '';
    // 纯相对时间（WorkBuddy 观感）：只说隔了多久——分钟 / 小时 / 天，不说具体钟点
    let diff = now.getTime() - d.getTime();
    if (diff < 0) diff = 0;
    const min = Math.floor(diff / 60000);
    if (min < 1) return '刚刚';
    if (min < 60) return min + '分钟前';
    const hr = Math.floor(min / 60);
    if (hr < 24) return hr + '小时前';
    // 跨天按日历日算，避免"昨天 23:59 看成 24小时前"这种别扭表述
    const days = Math.floor((new Date(now.getFullYear(), now.getMonth(), now.getDate())
      - new Date(d.getFullYear(), d.getMonth(), d.getDate())) / 86400000);
    if (days < 30) return days + '天前';
    return (d.getMonth() + 1) + '/' + d.getDate();
  }

  // 会话切换回调（首页注册，功能页默认跳回首页定位）
  let sessionChangeHandler = null;
  let sessionsSyncedHandler = null;
  let currentSessionId = '';
  /** 整页跳转统一遮罩：盖住旧页面再跳，避免旧页面留屏被看成"闪一下" */
  function navWithMask(href) {
    if (!href || document.getElementById('consoleNavMask')) return;
    // 标记带到新文档：旧页面的遮罩会随文档销毁，新页面必须在 head 第一帧自己盖住白屏
    try { sessionStorage.setItem('consoleNavPending', href); } catch (e) { }
    const mask = document.createElement('div');
    mask.id = 'consoleNavMask';
    mask.className = 'console-nav-mask';
    mask.innerHTML = '<div class="console-nav-spinner"></div><span>正在打开页面…</span>';
    document.body.appendChild(mask);
    requestAnimationFrame(function () {
      setTimeout(function () { location.href = href; }, 90);
    });
  }


  global.Shell = {
    setSession(id) { currentSessionId = id; renderSessions(); },
    onSessionChange(fn) { sessionChangeHandler = fn; },
    /** 服务端清单合并完成后通知页面（页面据此决定要不要切会话） */
    onSessionsSynced(fn) { sessionsSyncedHandler = fn; },
    syncSessions: syncSessions,
    pushSessionRemote: pushSessionRemote,
    patchSessionRemote: patchSessionRemote,
    getSessions: loadSessions,
    saveSessions,
    /** 相对时间格式化（今天 HH:MM / 昨天 / N天前 / M/D）：个人中心等处复用 */
    fmtTime: fmtTime,
    roleLabel: groupLabel,
    avatar: avatar,
    logout: confirmLogout,
    /** 选文件后先列清单再上传（知识库文档页也复用这套） */
    openUploadPicker: openUploadPicker,
    /** 问答页上传：选完文件直接入库，不跳页面 */
    uploadFiles: uploadFiles,
    /** 选文件夹批量上传：优先目录选择器，避免浏览器原生"上传到此网站"确认 */
    pickFolder: pickFolder,
    /** 分片树状预览（后台文档管理与知识库文档页共用） */
    showChunks: showChunks,
    refreshDocs() { return loadDocs(); },
    getCurrentUser() { const a = global.WrkAuth; return a ? a.getUser() : null; },
    groupLabel: groupLabel,
    tenantLabel: tenantLabel,
    /** 重新拉组织名字典（后台改完组织名后调用，界面立即换成新名字） */
    loadOrgLabels: loadOrgLabels,
    tenantLabels() { return Object.assign({}, TENANT_LABELS); },
    /** 用后台已拿到的组织清单直接覆盖字典，省一次 /api/orgs 请求 */
    setTenantLabels(map) {
      if (!map || typeof map !== 'object') return;
      TENANT_LABELS = map;
    },
    /** 组织改名后重绘侧栏用户块（显示的是"账号 · 组织名"） */
    renderUser: renderUser,
    hasPermission: hasPermission,
    /** 页面内动态加按钮后主动收敛（MutationObserver 通常已自动处理） */
    applyPermControls: applyPermControls,
    /** 手动触发一次页面权限发现（调试/新按钮上线后可用） */
    discoverPermissions: discoverPermissions,
    /** 整页跳转前盖遮罩，避免旧页面留屏 */
    navWithMask: navWithMask,
    /** 等权限清单就绪：功能页守卫用它保证判定时权限已加载 */
    permsReady: function () { return loadPermissions(); },
    /** 后台改完角色权限后调用，强制重拉（返回 Promise） */
    refreshPermissions: refreshPermissions,
    /** 诊断用：确认会话切换钩子是否真的绑上了（前端行为异常时先查这里） */
    handlerBound: function () { return typeof sessionChangeHandler === 'function'; },
    /** 供页面主动清理：删除会话时统一走这里，保证缓存/后端历史/首页副本一起清 */
    purgeSession: purgeSession,
    modal: openModal,
    closeModal: closeModal,
    /** 在弹框内显示错误（不要用 toast：弹框盖住屏幕时右下角提示经常被裁掉） */
    modalError: modalError,
    clearModalError: clearModalError
  };

  function pickSession(id, ev) {
    if (ev) ev.stopPropagation();
    const onHome = location.pathname.indexOf('index.html') >= 0 || location.pathname === '/' || location.pathname.endsWith('/');
    if (onHome && sessionChangeHandler) {
      sessionChangeHandler(id);
    } else {
      location.href = 'index.html?sid=' + encodeURIComponent(id);
    }
  }
  function newSession() {
    const onHome = location.pathname.indexOf('index.html') >= 0 || location.pathname === '/' || location.pathname.endsWith('/');
    if (onHome && sessionChangeHandler) {
      sessionChangeHandler('__new__');
    } else {
      location.href = 'index.html?new=1';
    }
  }
  /**
   * 彻底删掉一个会话：本地消息缓存 + 后端历史树 + 首页内存副本，三处一起清。
   *
   * <p>只删 localStorage 里的会话列表是不够的：首页在内存里还留着一份 sessions 副本，
   * 下一次发消息调 saveSessions() 会把整份旧列表写回去，被删的会话就"自己回来了"。
   * 这是之前那个 bug 的真正原因。</p>
   */
  function purgeSession(id) {
    try {
      const a = global.WrkAuth;
      const scope = a && a.scope ? a.scope() : 'demo';
      global.localStorage.removeItem('water-rag-msgs:' + scope + ':' + id);
    } catch (e) { /* 缓存清不掉不影响主流程 */ }
    // 服务端一条接口同时收掉"清单行 + Redis 消息树"，避免列表删了内容还留着占空间
    deleteSessionRemote(id);
    // 通知首页同步它内存里的那份副本（只有首页注册了钩子）。
    // 参数顺序：第一个永远是会话 id，第二个才是动作类型——与其它调用点保持一致，
    // 反过来传会让首页把 '__delete__' 当成会话 id 去切换，删了等于没删。
    if (typeof sessionChangeHandler === 'function') sessionChangeHandler(id, '__delete__');
  }

  function removeSession(id, ev) {
    ev.stopPropagation();
    openModal({ title: '删除会话', danger: true, okText: '确认删除',
      body: '<p style="margin:0">删除后该会话的问答记录与本地缓存都会清除，无法恢复，确定继续吗？</p>',
      onOk: function (close) {
        const list = loadSessions().filter(s => s.id !== id);
        saveSessions(list);
        purgeSession(id);
        close();
        // 首页由它自己的钩子切换视图；其它页面（检索对比/分支页）没有钩子，这里跳转
        if (typeof sessionChangeHandler !== 'function' && currentSessionId === id) {
          if (list.length) pickSession(list[0].id); else newSession();
        }
        renderSessions();
      }
    });
  }

  function renderSessions() {
    const el = document.getElementById('sessionList');
    if (!el) return;
    const list = loadSessions().slice().sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
    if (!list.length) { el.innerHTML = '<div class="session-empty">暂无会话，点击上方新建</div>'; return; }
    el.innerHTML = list.map(s => {
      const short = String(s.id || '').startsWith('session-') ? s.id.slice(8, 14) : String(s.id || '').slice(0, 8);
      return '<div class="session-item' + (s.id === currentSessionId ? ' active' : '') + '" data-sid="' + esc(s.id) + '" title="' + esc(s.title || '') + '">' +
        '<span class="session-name">' + esc(s.title || ('会话 ' + short)) + '</span>' +
        '<span class="session-time">' + esc(fmtTime(s.updatedAt)) + '</span>' +
        '<button class="session-delete" title="删除会话">×</button></div>';
    }).join('');
    el.querySelectorAll('.session-item').forEach(item => {
      const id = item.getAttribute('data-sid');
      item.onclick = () => pickSession(id);
      item.querySelector('.session-delete').onclick = ev => removeSession(id, ev);
    });
  }

  // ---------- 上传折叠区 ----------
  let supportedExts = new Set();
  let maxFileBytes = 0;
  const fmtSize = b => b >= 1073741824 ? (b / 1073741824).toFixed(1) + 'GB' : (b / 1048576).toFixed(1) + 'MB';
  const sizeError = f => maxFileBytes && f.size > maxFileBytes ? '文件 ' + fmtSize(f.size) + ' 超过单文件上限 ' + fmtSize(maxFileBytes) : null;
  const JUNK_FILES = new Set(['desktop.ini', 'thumbs.db', '.ds_store', '~$temp.hta']);
  const isJunkFile = name => name.startsWith('.') || JUNK_FILES.has(name.toLowerCase());

  function uploadMsg(text, type) {
    const el = document.getElementById('uploadMsg');
    if (!el) return;
    const area = document.getElementById('uploadArea');
    if (area) area.classList.add('open');
    el.className = 'upload-msg show' + (type ? ' ' + type : '');
    el.style.display = 'block';
    if (text !== undefined) el.textContent = text;
  }
  // 上传时把页面上「含下级部门可见」勾选一起带给后端（各页面共用同一勾选框 id）
  function appendVisibility(fd) {
    const sub = document.getElementById('includeSubtree');
    if (sub && sub.checked) fd.append('includeSubtree', 'true');
  }
  async function uploadOne(file) {
    if (!file) return;
    const oversize = sizeError(file);
    if (oversize) { uploadMsg('✗ ' + file.name + '：' + oversize, 'err'); return; }
    uploadMsg('正在解析 ' + file.name + '（' + fmtSize(file.size) + '）…');
    const fd = new FormData(); fd.append('file', file);
    appendVisibility(fd);
    const started = Date.now();
    try {
      const r = await fetch('/api/docs/upload', { method: 'POST', body: fd });
      const j = await r.json();
      if (!r.ok || j.chunks === undefined) throw new Error(j.message || ('上传失败（HTTP ' + r.status + '）'));
      const secs = ((Date.now() - started) / 1000).toFixed(1);
      uploadMsg('✓ 解析完成：' + file.name + ' 共 ' + j.chunks + ' 个切片（' + j.chars + ' 字），耗时 ' + secs + ' 秒，已可提问', 'ok');
      toast('文档已入库，现在可以提问了', 'ok');
      loadDocs();
    } catch (e) { uploadMsg('✗ ' + file.name + ' 入库失败：' + e.message, 'err'); }
  }
  /** 单个文件入库，成功返回切片数，失败抛中文原因 */
  async function ingestOne(file) {
    const oversize = sizeError(file);
    if (oversize) throw new Error(oversize);
    const fd = new FormData(); fd.append('file', file);
    appendVisibility(fd);
    const started = Date.now();
    let r;
    try {
      r = await fetch('/api/docs/upload', { method: 'POST', body: fd });
    } catch (e) {
      // 浏览器网络层失败只会抛英文 "Failed to fetch"，直接贴给用户看不懂，
      // 转成能指导下一步动作的中文原因（HTTP 层失败仍走下面的 j.message）
      throw new Error('网络连接失败，请检查网络或稍后重试');
    }
    const j = await r.json().catch(function () { return {}; });
    if (!r.ok || j.chunks === undefined) throw new Error(j.message || ('上传失败（HTTP ' + r.status + '）'));
    return { chunks: j.chunks, chars: j.chars, secs: ((Date.now() - started) / 1000).toFixed(1) };
  }

  function extOf(name) {
    const i = String(name).lastIndexOf('.');
    return i < 0 ? '' : String(name).slice(i + 1).toLowerCase();
  }

  /** 把待上传文件变成可展示的候选清单：分出"可上传 / 格式不支持 / 超过上限"三类 */
  function collectCandidates(all) {
    // 兼容三种入参：input 的 FileList、File 数组、目录选择器产出的 {file, rel}
    // （File System Access API 拿到的 File 没有 webkitRelativePath，相对路径得自己带过来）
    const raw = Array.from(all || []).map(function (x) {
      return (x && x.file) ? x : { file: x, rel: (x && x.webkitRelativePath) || '' };
    }).filter(function (x) { return x.file && !isJunkFile(x.file.name); });
    return raw.map(function (x) {
      const f = x.file;
      const ext = extOf(f.name);
      const supported = !supportedExts.size || supportedExts.has(ext);
      const oversize = !!(maxFileBytes && f.size > maxFileBytes);
      const rel = x.rel || '';
      const cut = rel.lastIndexOf('/');
      return {
        file: f, name: f.name, size: f.size, ext: ext,
        dir: cut > 0 ? rel.slice(0, cut) : '',
        supported: supported, oversize: oversize
      };
    });
  }

  // 批量上传时的运行态（清单、进度、是否已结束）
  let pickState = null;

  function pickerRow(it, i) {
    const bad = !it.supported || it.oversize;
    const tag = !it.supported ? '<span class="pick-tag">格式不支持</span>'
      : it.oversize ? '<span class="pick-tag err">超过单文件上限</span>'
        : '<span class="pick-tag ok">就绪</span>';
    return '<label class="pick-row' + (bad ? ' skip' : '') + '" data-i="' + i + '">' +
      '<input type="checkbox" class="pick-cb"' + (bad ? ' disabled' : ' checked') + '>' +
      '<span class="pick-name" title="' + esc(it.name) + '">' + esc(it.name) + '</span>' +
      (it.dir ? '<span class="pick-path" title="' + esc(it.dir) + '">' + esc(it.dir) + '</span>' : '') +
      '<span class="pick-size">' + fmtSize(it.size || 0) + '</span>' + tag + '</label>';
  }

  /**
   * 选文件夹。
   *
   * <p>优先用 File System Access 的 showDirectoryPicker：webkitdirectory 的 input 会让
   * 浏览器弹一条「将 N 个文件上传到此网站?」的原生确认，看着像浏览器在警告我们的产品，
   * 而这句提示用 CSS/JS 消不掉。目录选择器只弹系统文件夹选择框，选完直接进我们的清单弹框。</p>
   *
   * <p>不支持的浏览器（Firefox/Safari、以及 http 内网地址——该 API 要求安全上下文）
   * 自动退回原来的 webkitdirectory input，功能不丢，只是会看到那条原生确认。</p>
   */
  /**
   * 选文件夹批量上传。
   *
   * <p>为什么优先用 showDirectoryPicker 而不是 &lt;input webkitdirectory&gt;：
   * 后者会弹浏览器原生的「将 N 个文件上传到此网站?」确认，CSS/JS 都消不掉；
   * 前者弹的是系统文件夹框，选完就能列清单。</p>
   *
   * <p>但那句「允许此网站查看和复制文件？」是 Chrome 的文件系统权限提示，
   * 属于浏览器安全边界，代码里没有"始终允许"这种选项，每次调用 picker 都会问一遍。
   * 能做的是<b>不重复调用 picker</b>：把上次选中的目录句柄留住，同一页面内再点
   * 先用 queryPermission 探一下权限还在不在，在就直接扫（不再弹框）。
   * 刷新页面后句柄丢失，需要重新授权一次——这是浏览器的规矩，绕不过去。</p>
   *
   * @param forceNew true = 忽略缓存重新选目录（界面上那个"换文件夹"）
   */
  let dirHandleCache = null;

  async function pickFolder(forceNew) {
    if (typeof global.showDirectoryPicker !== 'function') {
      const inp = document.getElementById('folderInput');
      if (inp) inp.click(); else toast('当前浏览器不支持选择文件夹', 'err');
      return;
    }
    let dir = null;
    if (!forceNew && dirHandleCache) {
      try {
        const st = await dirHandleCache.queryPermission({ mode: 'read' });
        if (st === 'granted') dir = dirHandleCache;
      } catch (e) { dir = null; }
    }
    if (!dir) {
      try {
        // id 让浏览器记住上次停在哪；startIn 给个合理起点
        dir = await global.showDirectoryPicker({ mode: 'read', id: 'kb-folder', startIn: 'documents' });
      } catch (e) {
        return;   // 用户取消选择，不用提示
      }
      dirHandleCache = dir;
      markFolderPicked();
    }
    const picked = [];
    const walk = async function (handle, prefix) {
      for await (const entry of handle.values()) {
        if (isJunkFile(entry.name)) continue;
        if (entry.kind === 'file') {
          try {
            const f = await entry.getFile();
            picked.push({ file: f, rel: prefix + entry.name });
          } catch (e) { /* 单个文件读不出来（被占用/无权限）跳过，不影响整批 */ }
        } else if (entry.kind === 'directory') {
          await walk(entry, prefix + entry.name + '/');
        }
      }
    };
    try {
      await walk(dir, '');
    } catch (e) {
      toast('读取文件夹失败：' + e.message, 'err');
      return;
    }
    if (!picked.length) { toast('该文件夹里没有可上传的文件', 'err'); return; }
    openUploadPicker(picked);
  }

  /** 记住目录之后，把"换文件夹"入口和说明亮出来，避免用户以为再也换不了 */
  function markFolderPicked() {
    const other = document.getElementById('folderOther');
    if (other) other.style.display = '';
    const hint = document.getElementById('folderHint');
    if (hint) hint.textContent = '这个文件夹已记住：再点按钮直接读取，不再弹授权框。换目录点右边的 ↻。';
  }

  /**
   * 选文件夹 / 多选文件后的第一步：列出清单让人自己挑，而不是一股脑全传。
   * 一个目录里常混着 zip、图片、临时文件，传完才发现垃圾进库，还得逐个删。
   */
  function openUploadPicker(all) {
    // 上一批还在跑就别开新清单：两个循环同时往同一个知识库灌，重复入库很难收拾
    if (uploadJob && uploadJob.running) {
      uploadMsg('✗ 上一批还在后台解析（' + uploadJob.done + '/' + uploadJob.total + '），等它跑完再传下一批', 'err');
      return;
    }
    const items = collectCandidates(all);
    if (!items.length) { uploadMsg('✗ 没有可上传的文件', 'err'); return; }
    const usable = items.filter(function (x) { return x.supported && !x.oversize; });
    if (!usable.length) {
      uploadMsg('✗ 这 ' + items.length + ' 个文件都不支持上传（可在下方格式说明里确认）', 'err');
      return;
    }
    pickState = { items: items, finished: false, done: 0, fail: 0 };
    const total = items.length, bad = total - usable.length;
    openModal({
      // 中号弹框：文件清单是"逐条核对"的活，460 装不下文件名+大小+状态三列，
      // 680 又会在 1366 屏上贴着边；560 刚好一屏看八九条
      title: '选择要上传的文件', size: 'md',
      okText: '上传所选（' + usable.length + '）',
      body:
        '<div class="pick-summary">' +
          '<label class="pick-all"><input type="checkbox" id="pickAll" checked>全选</label>' +
          '<span class="pick-count" id="pickCount"></span>' +
          '<span class="pick-tip">取消勾选不需要的文件' + (bad ? '，' + bad + ' 个已自动排除' : '') + '</span>' +
        '</div>' +
        '<div class="pick-list" id="pickList">' + items.map(pickerRow).join('') + '</div>' +
        '<div class="pick-bar" id="pickBar"><i></i></div>' +
        '<div class="pick-done-tip" id="pickDone"></div>',
      onOk: async function () {
        if (pickState && pickState.finished) return true;  // 已传完，再点就是关闭
        return await runPickedUpload();
      }
    });
    const list = document.getElementById('pickList');
    function refreshCount() {
      const rows = Array.prototype.slice.call(list.querySelectorAll('.pick-row:not(.skip)'));
      const on = rows.filter(function (r) { return r.querySelector('.pick-cb').checked; });
      const bytes = on.reduce(function (s, r) {
        return s + (pickState.items[Number(r.dataset.i)].size || 0);
      }, 0);
      document.getElementById('pickCount').textContent =
        '已选 ' + on.length + ' / ' + rows.length + ' 个 · 合计 ' + fmtSize(bytes || 0);
      const cb = document.getElementById('pickAll');
      if (cb) {
        cb.checked = rows.length > 0 && on.length === rows.length;
        cb.indeterminate = on.length > 0 && on.length < rows.length;
      }
      const okBtn = document.querySelector('#appModal .btn-ok');
      if (okBtn && pickState && !pickState.finished) {
        okBtn.textContent = '上传所选（' + on.length + '）';
        okBtn.disabled = on.length === 0;
      }
    }
    list.addEventListener('change', function (e) {
      if (e.target.classList.contains('pick-cb')) refreshCount();
    });
    document.getElementById('pickAll').addEventListener('change', function (e) {
      list.querySelectorAll('.pick-row:not(.skip) .pick-cb').forEach(function (cb) { cb.checked = e.target.checked; });
      refreshCount();
    });
    refreshCount();
  }

  /** 逐条上传并回写每行状态；返回 false 表示"不要关弹框" */
  /**
   * 上传任务状态，与弹框 DOM 解耦。
   *
   * <p>文件是一个一个 POST 上去的，只要页面还在，请求就会继续跑完；但 closeModal
   * 会把 .modal-mask 的 innerHTML 清空，之前那版在循环末尾直接取 #pickDone 更新结果，
   * 一关弹框就抛 TypeError，收尾的 toast 与列表刷新全被吞掉——用户看到的是
   * "关掉之后再也没反应"，其实文件已经进库了。</p>
   *
   * <p>现在每处 UI 更新都先判节点还在不在（isConnected），收尾只按任务本身结算，
   * 弹框在就顺便刷弹框、不在就只提示。上传中途关窗口 = 转到后台继续，不中断。</p>
   */
  let uploadJob = null;

  async function runPickedUpload() {
    if (uploadJob && uploadJob.running) {
      modalError('上一批还在后台解析（' + uploadJob.done + '/' + uploadJob.total + '），等它跑完再传新的');
      return false;
    }
    const list = document.getElementById('pickList');
    if (!list || !pickState) return true;
    const rows = Array.prototype.slice.call(list.querySelectorAll('.pick-row:not(.skip)'))
      .filter(function (r) { return r.querySelector('.pick-cb').checked; });
    if (!rows.length) { modalError('请至少勾选一个文件'); return false; }
    clearModalError();
    const job = { running: true, total: rows.length, done: 0, fail: 0, token: pickState };
    uploadJob = job;
    const okBtn = document.querySelector('#appModal .btn-ok');
    const cancelBtn = document.querySelector('#appModal .btn-cancel');
    if (okBtn) { okBtn.disabled = true; okBtn.textContent = '上传中…'; }
    if (cancelBtn) cancelBtn.disabled = true;
    // 标题只写状态；「可关闭窗口」是小字副提示，不能和标题同级抢视觉
    const titleEl = document.querySelector('#appModal .modal-title');
    if (titleEl) titleEl.textContent = '上传中';
    const subEl = document.getElementById('modalSubtitle');
    if (subEl) subEl.textContent = '可关闭窗口，后台会继续';
    list.querySelectorAll('.pick-cb').forEach(function (cb) { cb.disabled = true; });
    const bar = document.getElementById('pickBar');
    if (bar) bar.classList.add('show');
    for (let k = 0; k < rows.length; k++) {
      const row = rows[k];
      const it = pickState.items[Number(row.dataset.i)];
      const tag = row.querySelector('.pick-tag');
      if (tag && row.isConnected) {
        tag.className = 'pick-tag run'; tag.textContent = '解析中…';
        row.style.background = '#f7faff';
      }
      if (it.dir) uploadMsg('正在解析 ' + it.name + '…');
      try {
        const res = await ingestOne(it.file);
        job.done++;
        if (row.isConnected && tag) {
          tag.className = 'pick-tag ok'; tag.textContent = '✓ ' + res.chunks + ' 片';
          row.style.background = '';
        }
      } catch (e) {
        job.fail++;
        if (row.isConnected && tag) {
          tag.className = 'pick-tag err'; tag.textContent = '✗ 失败';
          row.title = it.name + '：' + e.message;
          row.style.background = '#fff7f9';
        }
      }
      if (bar && bar.isConnected) {
        const fill = bar.querySelector('i');
        if (fill) fill.style.width = Math.round(((k + 1) / rows.length) * 100) + '%';
      }
      if (it.dir) uploadMsg('[' + (k + 1) + '/' + rows.length + '] ' + it.name);
    }
    job.running = false;
    const done = job.done, fail = job.fail;
    // 弹框还在、且还是我这一批（没被新清单顶掉）才更新弹框
    if (job.token === pickState) {
      pickState.finished = true;
      pickState.done = done; pickState.fail = fail;
    }
    const dt = document.getElementById('pickDone');
    if (dt && job.token === pickState) {
      dt.className = 'pick-done-tip show';
      dt.innerHTML = (done ? '✓ ' : '') + '完成：成功 <b>' + done + '</b> 个' +
        (fail ? '，失败 <b style="color:var(--danger)">' + fail + '</b> 个（鼠标悬停可看原因）' : '') +
        (done ? '。文档已入库，现在可以提问了。' : '。没有文档入库，请按失败原因处理后重试。');
      if (okBtn) { okBtn.disabled = false; okBtn.textContent = '完成'; }
      if (cancelBtn) cancelBtn.disabled = false;
      const t2 = document.querySelector('#appModal .modal-title');
      if (t2) t2.textContent = '选择要上传的文件';
      const s2 = document.getElementById('modalSubtitle');
      if (s2) s2.textContent = '';
    }
    toast('文档已入库 ' + done + ' 个' + (fail ? '（' + fail + ' 个失败）' : ''), fail ? 'err' : 'ok');
    loadDocs();
    return false;   // 保持弹框打开，让用户看到每个文件的结果
  }

  /** 单文件直接传（不多一步弹框，避免为了传一个文件还要点两次） */
  async function uploadFiles(all) {
    if (uploadJob && uploadJob.running) {
      uploadMsg('✗ 上一批还在后台解析（' + uploadJob.done + '/' + uploadJob.total + '），等它跑完再传', 'err');
      return;
    }
    const items = collectCandidates(all);
    const valid = items.filter(function (x) { return x.supported && !x.oversize; });
    if (!valid.length) {
      uploadMsg('✗ 没有支持的文档格式（共 ' + items.length + ' 个文件跳过）', 'err');
      return;
    }
    const area = document.getElementById('uploadArea'); if (area) area.classList.add('open');
    let ok = 0;
    for (let i = 0; i < valid.length; i++) {
      const f = valid[i].file;
      uploadMsg('[' + (i + 1) + '/' + valid.length + '] 正在解析 ' + f.name + '…');
      try {
        const res = await ingestOne(f);
        ok++;
        uploadMsg('[' + (i + 1) + '/' + valid.length + '] ✓ ' + f.name + '（' + res.chunks + ' 切片，' + res.secs + ' 秒）');
      } catch (e) { uploadMsg('[' + (i + 1) + '/' + valid.length + '] ✗ ' + f.name + '：' + e.message, 'err'); }
    }
    uploadMsg('✓ 批量上传处理完成，共 ' + ok + ' 个文件', 'ok');
    toast('文档已入库', 'ok');
    loadDocs();
  }

  // 侧栏只保留上传入口，文档查看/删除统一在「知识库文档」页面完成
  function loadDocs() {
    // 不在这里拉文档清单：侧栏没有列表。上传完成后用事件通知各页面自行刷新
    try { document.dispatchEvent(new CustomEvent('docs:updated')); } catch (e) {}
    return Promise.resolve();
  }

  // ---------- 文档分片树状预览 ----------
  // "为什么检索到这一段"是 RAG 最常被追问的问题，能点开每片原文比任何说明都直观。
  // 层级只用缩进 + 引导线表达，不做折叠面板那种重控件。
  async function showChunks(fileName) {
    openModal({
      title: '分片预览 · ' + fileName, wide: true, okText: '关闭', cancelText: '',
      body: '<div class="chunk-empty">正在读取分片…</div>'
    });
    try {
      const r = await fetch('/api/docs/chunks?fileName=' + encodeURIComponent(fileName));
      const j = await r.json().catch(function () { return {}; });
      if (!r.ok) throw new Error(j.message || ('读取失败（HTTP ' + r.status + '）'));
      renderChunkTree(fileName, j.chunks || [], null, j.total, j.truncated);
    } catch (e) {
      renderChunkTree(fileName, [], e.message);
    }
  }
  function renderChunkTree(fileName, chunks, err, totalCount, truncated) {
    const box = document.querySelector('#appModal .modal-body');
    if (!box) return;
    if (err) { box.innerHTML = '<div class="chunk-empty">加载失败：' + esc(err) + '</div>'; return; }
    if (!chunks || !chunks.length) { box.innerHTML = '<div class="chunk-empty">这份文档还没有可预览的分片</div>'; return; }
    const total = chunks.reduce(function (s, c) { return s + String(c.content || '').length; }, 0);
    const realTotal = totalCount || chunks.length;
    box.innerHTML = '<div class="chunk-tree">' +
      '<div class="chunk-root"><span class="cf-name" title="' + esc(fileName) + '">' + esc(fileName) + '</span>' +
      '<span class="tag">共 ' + realTotal + ' 片</span><span class="tag off">已显示 ' + chunks.length + ' 片 · ' + total.toLocaleString('zh-CN') + ' 字</span>' +
      '<button class="btn btn-sm chunk-all" style="margin-left:auto">全部展开</button></div>' +
      (truncated ? '<div class="chunk-empty" style="padding:10px 14px">文档过大，仅显示前 ' + chunks.length + ' 片；检索仍覆盖全文。</div>' : '') +
      chunks.map(function (c, k) {
        const txt = String(c.content || '');
        const open = k === 0;
        const long = txt.length > 240;
        return '<div class="chunk-item' + (open ? ' open' : '') + '">' +
          '<div class="chunk-head">' +
            '<span class="chunk-caret">' + (open ? '▾' : '▸') + '</span>' +
            '<span class="chunk-no">第 ' + (Number(c.chunkIndex || 0) + 1) + ' 片</span>' +
            '<span class="chunk-preview">' + esc(txt.slice(0, 90).replace(/\s+/g, ' ')) + '</span>' +
            '<span class="chunk-len">' + txt.length + ' 字</span>' +
          '</div>' +
          '<div class="chunk-body">' + esc(txt) + '</div>' +
          (long ? '<button type="button" class="chunk-more" data-len="' + txt.length + '">展开全文（' + txt.length + ' 字）</button>' : '') +
          '</div>';
      }).join('') + '</div>';
    // 事件用 addEventListener 绑定而不是内联 onclick：这些函数在 IIFE 内部，挂不到 window 上
    box.querySelectorAll('.chunk-head').forEach(function (h) {
      h.onclick = function () {
        const item = h.parentNode;
        item.classList.toggle('open');
        const c = item.querySelector('.chunk-caret');
        if (c) c.textContent = item.classList.contains('open') ? '▾' : '▸';
      };
    });
    const all = box.querySelector('.chunk-all');
    if (all) all.onclick = function () {
      const items = Array.prototype.slice.call(box.querySelectorAll('.chunk-item'));
      const anyClosed = items.some(function (x) { return !x.classList.contains('open'); });
      items.forEach(function (x) {
        x.classList.toggle('open', anyClosed);
        const c = x.querySelector('.chunk-caret');
        if (c) c.textContent = anyClosed ? '▾' : '▸';
      });
      all.textContent = anyClosed ? '全部收起' : '全部展开';
    };
    box.querySelectorAll('.chunk-more').forEach(function (b) {
      b.onclick = function (e) {
        e.stopPropagation();          // 别把点击冒泡到 .chunk-head 去折叠整片
        const item = b.parentNode;
        const full = item.classList.toggle('full');
        b.textContent = full ? '收起' : ('展开全文（' + b.dataset.len + ' 字）');
      };
    });
  }

  // 递归遍历拖入的文件/目录条目（readEntries 每次最多返回 100 条，需循环读完）
  function walkEntry(entry, out) {
    return new Promise(resolve => {
      if (entry.isFile) {
        entry.file(f => { out.push(f); resolve(); }, () => resolve());
      } else if (entry.isDirectory) {
        const reader = entry.createReader();
        const readBatch = () => reader.readEntries(async ents => {
          if (!ents.length) { resolve(); return; }
          for (const en of ents) await walkEntry(en, out);
          readBatch();
        }, () => resolve());
        readBatch();
      } else resolve();
    });
  }

  function bindSidebarEvents() {
    // 侧栏已移除快速上传区；上传统一走「知识库文档」页的上传卡。
    // 查询必须限定在侧栏内——否则会绑到 manage.html 自己的 #dropzone 并覆盖页面内的处理函数
    const host = document.getElementById('sidebarHost');
    const dz = host && host.querySelector('#dropzone');
    if (dz) {
      dz.onclick = () => document.getElementById('fileInput') && document.getElementById('fileInput').click();
      dz.ondragover = e => { e.preventDefault(); dz.classList.add('dragging'); };
      dz.ondragleave = e => { e.preventDefault(); dz.classList.remove('dragging'); };
      dz.ondrop = e => {
        e.preventDefault(); dz.classList.remove('dragging');
        const items = e.dataTransfer.items;
        // 拖的是文件夹：递归收集所有文件，然后列清单让人挑（不直接全传）
        if (items && items.length && items[0] && items[0].webkitGetAsEntry) {
          const files = [];
          (async () => {
            for (const item of items) {
              const entry = item.webkitGetAsEntry();
              if (entry) await walkEntry(entry, files);
            }
            if (files.length) openUploadPicker(files);
          })();
        } else {
          const fs = e.dataTransfer.files;
          // 一次拖多个也走清单；只拖一个就直接传，省掉一次确认
          if (fs && fs.length > 1) openUploadPicker(fs); else uploadFiles(fs);
        }
      };
    }
    const fi = host && host.querySelector('#fileInput');
    if (fi) fi.onchange = e => { uploadFiles(e.target.files); e.target.value = ''; };
    // 选文件夹：先列清单，用户勾掉不要的再传
    const folder = host && host.querySelector('#folderInput');
    if (folder) folder.onchange = e => { openUploadPicker(e.target.files); e.target.value = ''; };
  }

  // ---------- 用户卡片 ----------
  function renderUser() {
    const a = global.WrkAuth;
    const u = a ? a.getUser() : null;
    const name = u ? (u.displayName || u.user) : '演示用户';
    const tenant = u ? u.tenant : 'default';
    const box = document.getElementById('sidebarUserBlock');
    if (!box) return;
    box.innerHTML =
      '<a class="user-avatar" href="profile.html" title="个人中心">' + avatar(name, 'md') + '</a>' +
      '<a class="user-meta" href="profile.html" title="个人中心">' +
        '<div class="user-name">' + esc(name) + '</div>' +
        '<div class="user-tenant">' + esc(tenantLabel(tenant)) + '</div></a>' +
      '<a class="user-icon-btn" href="settings.html" title="设置">' + svg('settings') + '</a>' +
      '<button class="user-logout" title="退出登录" onclick="Shell.logout()">⏻</button>';
  }

  /** 退出登录：一步点了就走太容易误触（这个按钮紧挨着设置），必须二次确认 */
  function confirmLogout() {
    // 只说用户会关心的事；没有问答权限的人不该看到草稿、历史对话这些无关说明
    const chatUser = hasPermission('chat:use');
    const detail = chatUser
      ? '还没发出的提问会留在这台设备上，下次用同一账号登录还能接着写；之前的对话记录不受影响。'
      : '下次用同一账号登录，会回到你上次使用的页面。';
    openModal({
      title: '确认退出登录？', okText: '退出登录', cancelText: '继续留在当前页面',
      body: '<p style="margin:0 0 8px">退出后需要重新登录才能继续使用，确定现在退出吗？</p>' +
        '<p style="margin:0;color:var(--muted);font-size:12px">' + detail + '</p>',
      onOk: function () { if (global.WrkAuth) global.WrkAuth.logout(); }
    });
  }

  // ---------- 侧栏 DOM（全站唯一模板）----------
  function renderSidebar(active) {
    const host = document.getElementById('sidebarHost');
    if (!host) return;
    const old = host.querySelector('.sidebar');
    if (old) old.remove();
    // 功能权限：登录后按用户组过滤导航（与后端 PermissionService 规则一致）；
    // 未登录的演示模式显示全部菜单，便于作品集现场演示
    const auth = global.WrkAuth;
    const user = auth ? auth.getUser() : null;
    const groups = user && Array.isArray(user.groups) ? user.groups : [];
    const isAdmin = groups.indexOf('admin') >= 0;
    const visible = !user ? NAV
      : NAV.filter(n => (!n.groups.length || isAdmin || n.groups.some(g => groups.indexOf(g) >= 0))
        // 权限码过滤：权限清单已加载时直接不渲染，避免"先闪现再摘除"
        && (!n.perm || ownedPermissions.size === 0 || hasPermission(n.perm)));
    const items = visible.map(n =>
      '<a class="nav-item' + (n.key === active ? ' active' : '') + '" href="' + n.href + '" title="' + n.label + '">' +
      svg(n.icon) + '<span>' + n.label + '</span></a>'
    ).join('');
    const aside = document.createElement('div');
    aside.innerHTML =
      '<aside class="sidebar">' +
        '<div class="brand"><div class="brand-mark has-logo"><img class="brand-logo" src="assets/logo.svg" alt="水利知识库"></div>' +
          '<div><div class="brand-title">水利知识库</div><div class="brand-sub">RAG 智能问答系统</div></div></div>' +
        '<div class="section-label" style="margin-top:0">工作台</div>' +
        '<nav class="nav-list">' + items + '</nav>' +
        '<button class="new-chat" onclick="Shell.newSession()">＋ 新建会话</button>' +
        '<div class="section-label">历史会话</div>' +
        '<div class="session-list" id="sessionList"></div>' +
        '<div class="user-block" id="sidebarUserBlock"></div>' +
        '<div class="sidebar-footer">水利知识库 · 让知识更好用</div>' +
      '</aside>';
    host.insertBefore(aside.firstElementChild, host.firstChild);

    // 暴露给内联 onclick 使用
    global.Shell.newSession = newSession;
    renderSessions();
    renderUser();
    renderAdminEntry();   // 用户卡刚渲染完就定位管理后台入口，别等权限请求回来才显示
    bindSidebarEvents();
    loadDocs();
    fetch('/api/docs/formats').then(r => r.json()).then(d => {
      const exts = d.extensions || [];
      supportedExts = new Set(exts);
      maxFileBytes = d.maxFileSizeBytes || 0;
      const f = document.getElementById('dropFormats');
      if (f) { f.textContent = exts.slice(0, 10).join(' / ') + (exts.length > 10 ? ' 等 ' + exts.length + ' 种格式' : ''); f.title = exts.join(' / '); }
    }).catch(() => {});
  }

  /** 管理域页面侧栏：与 admin.html 同一套导航，按权限过滤，避免审计员看到问答侧栏 */
  function renderAdminSidebar(active) {
    const host = document.getElementById('sidebarHost');
    if (!host) return;
    const old = host.querySelector('.sidebar');
    if (old) old.remove();
    // 菜单数据以服务端权限目录为准（名称/路由/权限都可写库）；
    // 图标与分组属于 UI 细节，保留前端映射。接口未就绪时用静态清单兜底。
    const serverMenus = global.__serverMenus || [];
    const navOf = (srv) => {
      const hit = ADMIN_NAV.find(x => x.perm === (srv.permissions || [])[0] ||
        srv.href === x.href || ('/' + srv.href) === x.href);
      return hit || { key: String(srv.href || '').replace(/\.html$/, ''), icon: '▤', perm: (srv.permissions || [])[0] || '' };
    };
    const items = serverMenus.length
      ? serverMenus.filter(m => m.allowed && !/^(index|manage|search|tree|profile)\.html$/.test(m.href))
          .map(m => Object.assign({}, navOf(m), { label: m.name, href: String(m.href).replace(/^\//, '') }))
      : ADMIN_NAV;
    const keySet = new Set(items.map(n => n.key));
    const navHtml = ADMIN_GROUPS.map(g => {
      const keys = g.keys.filter(k => keySet.has(k));
      if (!keys.length) return '';
      return '<div class="admin-nav-group">' +
        '<div class="section-label" style="margin:14px 0 6px">' + g.label + '</div>' +
        keys.map(k => {
          const n = items.find(x => x.key === k);
          if (!n) return '';
          return '<a class="admin-nav-item' + (n.key === active ? ' active' : '') + '"' +
            ' href="' + n.href + '" data-panel="' + n.key + '" data-perm="' + n.perm + '" title="' + n.label + '">' +
            '<span class="ic">' + n.icon + '</span>' + n.label + '</a>';
        }).join('') + '</div>';
    }).join('') || items.map(n =>
      '<a class="admin-nav-item' + (n.key === active ? ' active' : '') + '" href="' + n.href +
      '" data-panel="' + n.key + '" data-perm="' + n.perm + '"><span class="ic">' + n.icon + '</span>' + n.label + '</a>'
    ).join('');
    const aside = document.createElement('div');
    aside.innerHTML =
      '<aside class="admin-sidebar">' +
        '<div class="brand"><div class="brand-mark has-logo"><img class="brand-logo" src="assets/logo.svg" alt=""></div>' +
          '<div><div class="brand-title">管理后台</div><div class="brand-sub">水利知识库</div></div></div>' +
        navHtml +
        '<div class="admin-back"><a class="admin-nav-item" href="index.html"><span class="ic">⌂</span>返回问答</a>' +
        '<div class="user-block" id="adminUserBlock"></div></div>' +
      '</aside>';
    host.insertBefore(aside.firstElementChild, host.firstChild);
    renderAdminEntry();
    const u = global.WrkAuth && global.WrkAuth.getUser() || {};
    const box = document.getElementById('adminUserBlock');
    if (box) box.innerHTML =
      '<a class="user-avatar" href="profile.html" title="个人中心">' + avatar(u.displayName || u.user || '管', 'md') + '</a>' +
      '<a class="user-meta" href="profile.html" title="个人中心"><div class="user-name">' + esc(u.displayName || u.user || '管') + '</div>' +
      '<div class="user-tenant">' + esc(tenantLabel(u.tenant)) + '</div></a>' +
      '<button class="user-logout" title="退出登录" onclick="Shell.logout()">⏻</button>';
    bindSidebarEvents();
  }

  // ---------- 权限码（前端只控制入口显示，真正拦截在服务端） ----------
  let ownedPermissions = new Set();
  let permsLoaded = false;
  function hasPermission(code) {
    if (ownedPermissions.has(code)) return true;
    // 细分上传权限继承父码 doc:write：老角色只勾了父码也照常看到三处上传入口，
    // 需要单独管控时再给角色配细分码（父码不自动替细分码放行反向不成立）
    if (typeof code === 'string' && code.startsWith('doc:write_') && ownedPermissions.has('doc:write')) return true;
    return false;
  }
  // 权限清单的加载 promise：功能页守卫要等它回来再判定，否则拿空集合会把有权限的人也拦住
  let permsReady = null;
  async function loadPermissions() {
    if (permsReady) return permsReady;
    permsReady = (async function () {
      try {
        const r = await fetch('/api/auth/permissions');
        if (!r.ok) {
          // 权限接口非 2xx：清空权限集并收敛菜单，不能让"权限加载失败"变成"全都可见"
          ownedPermissions = new Set();
          permsLoaded = false;
          applySidebarPerms(); applyPermControls(); renderAdminEntry();
          return false;
        }
        const j = await r.json();
        ownedPermissions = new Set(j.permissions || []);
        // 菜单与导航结构以服务端权限目录为准；接口失败时保留静态兜底
        if (Array.isArray(j.menus) && j.menus.length) global.__serverMenus = j.menus;
        permsLoaded = true;
        renderAdminEntry();
        applySidebarPerms(); applyPermControls();
        // 页面新增/调整 data-perm 后，管理员访问一次就自动补进权限树，无需手改目录
        if (hasPermission('admin:access')) setTimeout(discoverPermissions, 1200);
        return true;
      } catch (e) {
        ownedPermissions = new Set();
        permsLoaded = false;
        applySidebarPerms(); applyPermControls(); renderAdminEntry();   // 网络失败同样按无权限收敛
        return false;
      }
    })();
    return permsReady;
  }
  /** 角色权限在后台改动后强制重拉，避免当前页面一直用旧权限缓存 */
  function refreshPermissions() {
    permsReady = null;
    return loadPermissions();
  }
  // 权限清单回来后二次收敛侧栏：renderSidebar 是同步渲染（当时权限多半还没回来），
  // 这里把无权限的导航项从 DOM 摘掉——菜单隐藏只是第一道门，页面内还有守卫
  function applySidebarPerms() {
    const host = document.getElementById('sidebarHost');
    if (!host) return;
    NAV.filter(n => n.perm && !hasPermission(n.perm)).forEach(n => {
      const el = host.querySelector('.nav-item[href="' + n.href + '"]');
      if (el) el.remove();
    });
    // 后台侧栏导航项统一带 data-perm，按权限码摘除（审计员只看得到日志审计组）
    host.querySelectorAll('.admin-nav-item[data-perm]').forEach(el => {
      if (!hasPermission(el.getAttribute('data-perm'))) el.remove();
    });
    // 一组里全被权限过滤掉时连组标题一起收起，避免侧栏出现"空组+标题"的错位空白
    host.querySelectorAll('.admin-nav-group').forEach(gr => {
      gr.style.display = gr.querySelector('.admin-nav-item') ? '' : 'none';
    });
  }
  /**
   * 全局按钮级权限：页面上任何元素都可以用 data-perm="权限码" 声明入口门槛。
   * 侧栏导航由 applySidebarPerms 负责摘除，这里处理页面内的按钮/工具条/面板。
   * 动态渲染的按钮由 MutationObserver 自动补挂，不必每个页面自己记得调用。
   */
  function applyPermControls(root) {
    const scope = root && root.querySelectorAll ? root : document;
    const nodes = scope.querySelectorAll ? scope.querySelectorAll('[data-perm]') : [];
    nodes.forEach(el => {
      const code = el.getAttribute('data-perm');
      if (!code) return;
      // 权限还没回来时先隐藏，拿到权限再放行——和侧栏一样采用默认拒绝
      if (permsLoaded && hasPermission(code)) {
        if (el.dataset.permHidden === '1') {
          el.style.display = el.dataset.permPrevDisplay || '';
          delete el.dataset.permHidden;
        }
      } else {
        if (el.dataset.permHidden !== '1') {
          el.dataset.permPrevDisplay = el.style.display || '';
          el.dataset.permHidden = '1';
        }
        el.style.display = 'none';
      }
    });
    // 分组里一个可见入口都没有时，连组标题一起收起，避免留下空目录
    document.querySelectorAll('.admin-nav-group').forEach(gr => {
      const any = Array.from(gr.querySelectorAll('.admin-nav-item'))
        .some(a => getComputedStyle(a).display !== 'none');
      gr.style.display = any ? '' : 'none';
    });
  }
  // 管理后台入口：管理员是高频用户，顶栏保留醒目的文字按钮（问页/文档页等都能一键进入）；
  // 左下角用户卡同时保留快捷入口。权限不足时两处都不显示。
  function renderAdminEntry() {
    // 同时拥有问答权限和任一后台权限才给入口；只进后台页不做问答的账号不需要这个按钮
    const pages = [
      ['admin:access', 'users.html'], ['user:read', 'users.html'], ['org:read', 'org.html'],
      ['role:read', 'roles.html'], ['perm:read', 'perms.html'], ['admin:docs', 'docs.html'],
      ['model:read', 'models.html'], ['usage:read', 'usage.html'],
      ['loginlog:read', 'loginlogs.html'], ['audit:read', 'audit.html']
    ];
    const canChat = hasPermission('chat:use');
    const target = canChat ? (pages.find(p => hasPermission(p[0])) || [])[1] : null;
    const inAdmin = /admin\.html|users\.html|org\.html|roles\.html|perms\.html|docs\.html|models\.html|usage\.html|loginlogs\.html|audit\.html/
      .test(location.pathname);
    const slot = document.getElementById('adminEntrySlot');
    if (slot) {
      slot.innerHTML = (target && !inAdmin)
        ? '<a class="admin-entry-btn" href="' + target + '" title="进入管理后台" onclick="Shell.navWithMask(\'' + target + '\')">⚙ 管理后台</a>' : '';
    }
  }

  // ---------- 服务探活 ----------
  async function checkHealth() {
    const pills = document.querySelectorAll('[data-status-pill]');
    if (!pills.length) return;
    let healthy = false;
    try { const j = await (await fetch('/api/search/health')).json(); healthy = !!j.healthy; }
    catch (e) { healthy = false; }
    pills.forEach(p => {
      // 记住元素自己的基础类（顶栏胶囊 status-pill / 侧栏状态条 sidebar-health），
      // 只叠加 off。整体重置 className 会把侧栏那条改成顶栏胶囊样式。
      if (!p.dataset.baseClass) {
        p.dataset.baseClass = (p.className || 'status-pill').replace(/\boff\b/g, '').trim() || 'status-pill';
      }
      p.className = p.dataset.baseClass + (healthy ? '' : ' off');
      p.innerHTML = '<span class="status-dot"></span>' + (healthy ? '服务正常' : '服务不可用');
    });
  }

  /**
   * 服务探活胶囊：右下角固定悬浮，全站只此一处。
   *
   * <p>放侧栏底部时它等于每个页面都多一行常驻文字（用户反馈"服务正常这个还是不太对"）；
   * 环境状态属于"想确认时才看一眼"的信息，固定右下角、不参与任何布局最省事，
   * 也不会像顶栏胶囊那样跟页面标题抢视线。</p>
   */
  function ensureHealthPill() {
    let el = document.getElementById('svcPill');
    if (!el) {
      el = document.createElement('div');
      el.id = 'svcPill';
      el.setAttribute('data-status-pill', '');
      el.title = '知识库检索服务状态（每 30 秒自动重试）';
      el.innerHTML = '<span class="status-dot"></span>检测中…';
      document.body.appendChild(el);
    }
    // 基础类固定为 svc-pill；healthy/off 由 checkHealth 每次重算，不在这里叠加
    if (!el.dataset.baseClass) el.dataset.baseClass = 'svc-pill';
    return el;
  }
  /** 老入口保留：管理后台以前单独往侧栏塞状态条，现在统一走右下角胶囊 */
  function injectAdminHealth() { ensureHealthPill(); }

  function toast(msg, type) {
    let t = document.getElementById('appToast');
    if (!t) { t = document.createElement('div'); t.id = 'appToast'; t.className = 'toast'; document.body.appendChild(t); }
    t.textContent = msg;
    t.className = 'toast show' + (type ? ' ' + type : '');
    clearTimeout(t._timer);
    t._timer = setTimeout(() => t.classList.remove('show'), 2600);
  }

  // ---------- 弹框内的错误横幅 ----------
  // 为什么不用 toast：弹框遮罩盖住大半屏时，右下角 toast 在矮屏/缩放下常被裁掉一半，
  // 用户只看到"点了没反应"。错误必须贴着嘴边的出错位置出现，而且不会自己消失。
  function modalError(msg) {
    const el = document.getElementById('modalAlert');
    if (!el) { toast(msg || '操作失败', 'err'); return; }
    // 每次显示都重建内容，保证关闭按钮的事件不丢
    el.innerHTML = '<span class="modal-alert-text">' + esc(msg || '操作失败') + '</span>' +
      '<button type="button" class="modal-alert-close" title="关闭提示" aria-label="关闭提示">×</button>';
    const btn = el.querySelector('.modal-alert-close');
    if (btn) btn.onclick = clearModalError;
    el.classList.add('show');
    const box = el.closest ? el.closest('.modal-box') : null;
    if (box) box.scrollTop = 0;
  }
  function clearModalError() {
    const el = document.getElementById('modalAlert');
    if (el) { el.classList.remove('show'); el.innerHTML = ''; }
  }

  // ---------- 全局自定义弹框（替代浏览器原生 confirm/prompt） ----------
  let modalState = null;
  function closeModal() {
    const mask = document.getElementById('appModal');
    // 上传还在跑：关窗口不等于中断。不说这一句，用户会以为白传了、再传一遍。
    if (uploadJob && uploadJob.running && mask && mask.querySelector('#pickList')) {
      toast('已转到后台继续解析，跑完会提示', 'ok');
    }
    if (mask) { mask.classList.remove('show'); mask.innerHTML = ''; }
    modalState = null;
    document.removeEventListener('keydown', onModalKey);
  }
  function onModalKey(e) { if (e.key === 'Escape') closeModal(); }
  /**
   * 打开弹框。opts: { title, body(HTML), okText, cancelText, onOk(closeFn), danger }
   * onOk 返回 false 时不关闭，便于表单校验失败时保留弹框。
   */
  function openModal(opts) {
    opts = opts || {};
    // cancelText 传空串 = 只要一个关闭/确定按钮（纯展示型弹框）。
    // 之前用 opts.cancelText || '取消' 兜底，空串被当成"没传"，于是每个只读弹框
    // 都多出一个毫无作用的「取消」，看着像两套操作。
    const showCancel = opts.cancelText !== '';
    let mask = document.getElementById('appModal');
    if (!mask) {
      mask = document.createElement('div');
      mask.id = 'appModal'; mask.className = 'modal-mask';
      document.body.appendChild(mask);
    }
    // 宽度三档：默认（460，表单/提示）、md（560，列表类）、wide（680，矩阵/树）
    const sizeCls = opts.wide ? ' modal-wide' : (opts.size === 'md' ? ' modal-md' : '');
    mask.innerHTML =
      '<div class="modal-box' + sizeCls + '" role="dialog" aria-modal="true">' +
        '<div class="modal-head' + (opts.danger ? ' danger' : '') + '"><div class="modal-title-wrap">' +
          '<div class="modal-title">' + (opts.danger ? '<span style="margin-right:6px">⚠</span>' : '') +
            esc(opts.title || '提示') + '</div>' +
          '<div class="modal-subtitle" id="modalSubtitle"></div>' +
        '</div>' +
        '<button class="modal-close" title="关闭">×</button></div>' +
        '<div class="modal-alert" id="modalAlert" role="alert"></div>' +
        '<div class="modal-body">' + (opts.body || '') + '</div>' +
        '<div class="modal-foot">' +
          (showCancel ? '<button class="btn btn-cancel">' + esc(opts.cancelText || '取消') + '</button>' : '') +
          '<button class="btn ' + (opts.danger ? 'btn-danger' : 'btn-primary') + ' btn-ok">' +
            esc(opts.okText || '确定') + '</button>' +
        '</div>' +
      '</div>';
    mask.classList.add('show');
    modalState = opts;
    mask.querySelector('.modal-close').onclick = closeModal;
    const cancelBtn = mask.querySelector('.btn-cancel');
    if (cancelBtn) cancelBtn.onclick = closeModal;
    mask.onclick = e => { if (e.target === mask) closeModal(); };
    mask.querySelector('.btn-ok').onclick = async () => {
      if (typeof opts.onOk !== 'function') { closeModal(); return; }
      let keepOpen = false;
      try {
        // 支持异步 onOk：表单保存接口返回 Promise，await 后才能判断要不要保留弹框
        keepOpen = (await opts.onOk(closeModal)) === false;
      } catch (e) {
        keepOpen = true;
        modalError(e && e.message ? e.message : '操作失败');
      }
      if (!keepOpen) closeModal();
    };
    document.addEventListener('keydown', onModalKey);
    const first = mask.querySelector('input,textarea,select');
    if (first) first.focus();
  }
  global.appModal = openModal;
  global.appModalClose = closeModal;

  // ---------- 设置类页面：返回键 + 可点击的层级面包屑 ----------
  const PAGE_NAV = {
    'settings.html': { label: '设置', back: 'index.html', backText: '返回问答' },
    'search.html': { label: '检索对比', parent: 'settings.html', parentLabel: '设置' },
    'tree.html': { label: '会话结构', parent: 'settings.html', parentLabel: '设置' },
    // 权限与日志属于管理域，左侧已有完整后台导航，顶栏不再重复塞"返回设置/设置/…"
    'profile.html': { label: '个人中心', back: 'index.html', backText: '返回问答' },
    'manage.html': { label: '知识库文档', back: 'index.html', backText: '返回问答' }
  };
  function renderTopbarNav() {
    const file = location.pathname.split('/').pop() || 'index.html';
    const meta = PAGE_NAV[file];
    const host = document.querySelector('.topbar-left');
    if (!meta || meta.noNav || !host || host.querySelector('.topbar-nav-left')) return;
    const backHref = meta.parent || meta.back || 'index.html';
    const backText = meta.parent ? '返回设置' : (meta.backText || '返回');
    const crumb = meta.parent
      ? '<a href="' + meta.parent + '">' + esc(meta.parentLabel) + '</a>' +
        '<span class="crumb-sep">/</span><span class="crumb-current">' + esc(meta.label) + '</span>'
      : '<span class="crumb-current">' + esc(meta.label) + '</span>';
    host.insertAdjacentHTML('afterbegin',
      '<div class="topbar-nav-left"><a class="back-link" href="' + backHref + '">' + backText + '</a>' +
      '<div class="crumb">' + crumb + '</div></div>');
    // 面包屑已经表达层级，隐藏原页面标题，避免顶栏重复显示同一名称
    const title = host.querySelector('.topbar-title');
    const sub = host.querySelector('.topbar-sub');
    if (title) title.style.display = 'none';
    if (sub) sub.style.display = 'none';
  }

  // 页面声明的权限码 → 父节点。只用于自动发现新节点；已存在的节点以库里的层级为准。
  const PERM_PARENT = {
    'chat:use': 'workbench:root', 'doc:read': 'workbench:root', 'search:use': 'workbench:root',
    'tree:use': 'workbench:root', 'doc:write': 'doc:read', 'doc:delete': 'doc:read',
    'admin:access': 'system:root', 'user:read': 'console.config:root', 'user:write': 'user:read',
    'org:read': 'console.config:root', 'role:read': 'console.config:root', 'perm:read': 'console.config:root',
    'admin:docs': 'console.biz:root', 'model:read': 'console.biz:root', 'model:write': 'model:read',
    'usage:read': 'console.biz:root', 'loginlog:read': 'console.log:root', 'audit:read': 'console.log:root'
  };
  let permissionDiscoveryDone = false;
  /** 收集本页声明式权限：按钮/链接上的 data-perm，名称直接取可见文案 */
  function collectPermDeclarations() {
    const seen = new Set(), out = [];
    const isAdminPage = /admin\.html/.test(location.pathname);
    document.querySelectorAll('button[data-perm],a[data-perm],[data-perm-kind]').forEach(el => {
      const code = el.getAttribute('data-perm');
      if (!code || seen.has(code)) return;
      seen.add(code);
      const declared = el.getAttribute('data-perm-kind');
      if (declared === 'directory') return;
      const type = declared || (el.tagName === 'A' || el.classList.contains('admin-nav-item') ? 'menu' : 'btn');
      const label = el.getAttribute('data-perm-name') ||
        (el.innerText || el.getAttribute('title') || code).trim().replace(/\s+/g, ' ').slice(0, 32);
      out.push({
        code: code, name: label || code, type: type,
        parentCode: el.getAttribute('data-perm-parent') || PERM_PARENT[code] || '',
        routePath: '/' + (location.pathname.split('/').pop() || 'index.html'),
        module: isAdminPage ? '系统管理' : '工作台'
      });
    });
    return out;
  }
  /** 声明式清单对账：权限树以 perm-manifest.js 为准，自定义节点服务端不碰 */
  async function discoverPermissions(force) {
    if ((!force && permissionDiscoveryDone) || !hasPermission('admin:access')) return;
    if (!global.PermManifest) return;
    permissionDiscoveryDone = true;
    try {
      const r = await fetch('/api/admin/perms/sync', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(global.PermManifest.flatten())
      });
      if (r.ok) {
        const j = await r.json();
        if ((j.added || []).length || (j.retired || []).length) console.info('权限清单已同步：', j);
      }
    } catch (e) { /* 自动发现失败不影响页面功能 */ }
  }

  // 页面级权限：静态页是白名单资源，服务端拦不到 HTML 本身，进入页面要靠前端守卫。
  // 权限码与权限目录（菜单权限页）的 routePath 一一对应，后台改角色权限后刷新即生效。
  const PAGE_PERMS = {
    'index.html': 'chat:use',
    'manage.html': 'doc:read',
    'search.html': 'search:use',
    'tree.html': 'tree:use',
    'models.html': 'model:read',
    'users.html': 'user:read',
    'org.html': 'org:read',
    'roles.html': 'role:read',
    'perms.html': 'perm:read',
    'docs.html': 'admin:docs',
    'models.html': 'model:read',
    'usage.html': 'usage:read',
    'loginlogs.html': 'loginlog:read',
    'audit.html': 'audit:read',
    'admin.html': 'admin:access'
  };
  const PAGE_PERM_NAMES = {
    'chat:use': '智能问答', 'doc:read': '知识库文档', 'search:use': '检索对比',
    'tree:use': '会话分支', 'model:read': '模型设置', 'admin:access': '管理后台',
    'audit:read': '审计日志'
  };
  function enforcePagePermission(file) {
    // 权限接口失败时不误拦页面（服务端接口仍有权限校验），只做入口收敛
    if (!permsLoaded) return;
    let code = PAGE_PERMS[file];
    // 后台页面按 panel 参数细分：日志面板只需对应日志权限，不必进管理后台
    if (file === 'admin.html') {
      const panel = new URLSearchParams(location.search).get('panel');
      if (panel === 'loginlogs') code = ['loginlog:read', 'admin:access'];
      if (panel === 'audit') code = ['audit:read', 'admin:access'];
    }
    if (!code) return;
    if (document.getElementById('permDeniedScreen')) return;
    const codes = Array.isArray(code) ? code : [code];
    if (codes.some(c => hasPermission(c))) return;
    const name = PAGE_PERM_NAMES[codes[0]] || codes[0];
    document.body.insertAdjacentHTML('beforeend',
      '<div class="perm-denied-screen" id="permDeniedScreen">' +
        '<div class="perm-denied-card">' +
          '<div class="perm-denied-mark">⊘</div>' +
          '<h2>没有访问权限</h2>' +
          '<p>当前账号没有「' + esc(name) + '」权限，无法打开此页面。如需使用，请联系管理员开通。</p>' +
          '<div class="perm-denied-actions">' +
            '<a class="perm-denied-primary" href="index.html">返回问答首页</a>' +
            '<button class="perm-denied-secondary" onclick="window.WrkAuth&&WrkAuth.logout&&WrkAuth.logout()">退出并换账号</button>' +
          '</div>' +
        '</div>' +
      '</div>');
  }

  global.initShell = function (active, opts) {
    // 未登录直接敲静态页地址也要弹回登录页（静态资源是后端白名单，前端必须自查）
    if (global.WrkAuth && !global.WrkAuth.guardPage()) return;
    opts = opts || {};
    const file = location.pathname.split('/').pop() || 'index.html';
    // 动态渲染出来的按钮（表格行、弹窗等）自动挂上权限收敛
    if (global.MutationObserver && !document.body.dataset.permObserverReady) {
      document.body.dataset.permObserverReady = '1';
      new MutationObserver(muts => muts.forEach(m => m.addedNodes.forEach(n => {
        if (n.nodeType === 1) applyPermControls(n);
      }))).observe(document.body, { childList: true, subtree: true });
    }
  /**
   * 后台软导航：users/org/roles/... 共用同一份 admin.html，没必要整页刷新。
   * 整页刷新会让旧页面（用户管理）在新文档加载完成前一直留在屏幕上，看起来就是"闪一下用户管理"。
   * 这里拦截侧栏链接：目标面板在当前 DOM 里就用 pushState + goPanel 原地切换。
   */
  function setupConsoleSoftNav() {
    if (document.body.dataset.softNavReady) return;
    document.body.dataset.softNavReady = '1';
    document.addEventListener('click', function (e) {
      const a = e.target.closest && e.target.closest('a.admin-nav-item[data-panel]');
      if (!a || e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
      const key = a.getAttribute('data-panel');
      const panel = document.querySelector('.admin-panel[data-panel="' + key + '"]');
      // 独立页面（models.html 等）不在当前 DOM 里，只能整页跳。
      // 先盖一层遮罩再跳，避免旧页面在新文档加载完成前一直留在屏幕上（用户看到的就是"闪一下"）。
      if (!panel || typeof window.goPanel !== 'function') {
        e.preventDefault();
        navWithMask(a.getAttribute('href'));
        return;
      }
      e.preventDefault();
      history.pushState({ panel: key }, '', a.getAttribute('href'));
      window.goPanel(key);
    });
    window.addEventListener('popstate', function () {
      if (typeof window.goPanel !== 'function') return;
      const file = location.pathname.split('/').pop() || '';
      const key = ({ 'users.html': 'users', 'org.html': 'org', 'roles.html': 'roles',
        'perms.html': 'perms', 'docs.html': 'docs', 'usage.html': 'usage',
        'loginlogs.html': 'loginlogs', 'audit.html': 'audit', 'admin.html': 'users' })[file];
      if (key && document.querySelector('.admin-panel[data-panel="' + key + '"]')) window.goPanel(key);
    });
  }

  // 管理域页面（如权限与日志）用后台侧栏；问答域保持原侧栏
  if (opts.adminSidebar) { renderAdminSidebar(active); setupConsoleSoftNav(); } else renderSidebar(active);
    renderTopbarNav();
    injectAdminHealth();
    checkHealth();
    renderAdminEntry();
    loadPermissions().then(ok => {
      if (!ok) return;                 // 加载失败不误拦，接口侧仍受服务端保护
      enforcePagePermission(file);
      applyPermControls();
    });
    // 组织名先按内置兜底渲染，拿到服务端字典后重绘侧栏用户块（组织名可能已被管理员改过）
    loadOrgLabels().then(ok => { if (ok) renderUser(); });
    // 管理域页面不碰问答会话清单，避免审计员登录后被塞进"新建会话"那套状态
    if (!opts.adminSidebar) syncSessions();
    if (global.setInterval) setInterval(checkHealth, 30000);
  };
  global.appToast = toast;
})(window);
