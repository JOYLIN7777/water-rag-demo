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
  const NAV = [
    { key: 'index', href: 'index.html', label: '智能问答', icon: 'chat', groups: [] },
    { key: 'manage', href: 'manage.html', label: '知识库文档', icon: 'doc', groups: [] }
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
   * 拉服务端清单并合并。
   *
   * <p>合并规则（服务端是真源）：
   * <ul>
   *   <li>服务端为空、本地有：升级路径，把本地会话逐条迁上去，本地保留；</li>
   *   <li>服务端非空：以服务端为准。本地孤儿条目（服务端已经没有的）默认清掉——
   *       以前永远保留，结果是"在别的端删掉的会话回来阴魂不散"，同一个标题出现好几条；
   *       只豁免 60 秒内的新条目，保护"刚提问、POST 还没落库、GET 先回来"的竞态窗口；</li>
   *   <li>服务端不可达：本地缓存原样保留，离线也能看历史。</li>
   * </ul></p>
   */
  function syncSessions() {
    // 并发去重：initShell 与页面 boot 都会调一次，共用同一个请求
    if (syncInFlight) return syncInFlight;
    syncInFlight = fetch('/api/chat/sessions').then(function (r) { return r.ok ? r.json() : null; }).then(function (rows) {
      if (!Array.isArray(rows)) return;
      const local = loadSessions();
      if (!rows.length && local.length) {
        // 升级路径：服务端还没有任何记录，把本地现有会话逐条迁上去
        local.forEach(function (s) { pushSessionRemote(s.id, s.title || ''); });
        saveSessions(local);
        renderSessions();
        if (typeof sessionsSyncedHandler === 'function') sessionsSyncedHandler();
        return;
      }
      const serverIds = {};
      rows.forEach(function (r) { serverIds[r.id] = 1; });
      // 只豁免 60 秒内创建的本地条目：那是刚 POST 还没落库的竞态，不是僵尸
      const freshCut = Date.now() - 60000;
      const orphans = local.filter(function (s) { return !serverIds[s.id] && (s.updatedAt || 0) >= freshCut; });
      const merged = rows.map(function (r) {
        return { id: r.id, title: r.title || '', updatedAt: r.updatedAt || 0 };
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
    // 相对时间（对齐 WorkBuddy 的清单观感）：今天给时间，昨天/更早给"N天前"
    if (d.toDateString() === now.toDateString()) {
      return '今天 ' + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
    }
    const days = Math.floor((new Date(now.getFullYear(), now.getMonth(), now.getDate())
      - new Date(d.getFullYear(), d.getMonth(), d.getDate())) / 86400000);
    if (days === 1) return '昨天';
    if (days >= 2 && days < 30) return days + '天前';
    return (d.getMonth() + 1) + '/' + d.getDate();
  }

  // 会话切换回调（首页注册，功能页默认跳回首页定位）
  let sessionChangeHandler = null;
  let sessionsSyncedHandler = null;
  let currentSessionId = '';
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
    avatar: avatar,
    logout: confirmLogout,
    /** 选文件后先列清单再上传（知识库文档页也复用这套） */
    openUploadPicker: openUploadPicker,
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
    /** 等权限清单就绪：功能页守卫用它保证判定时权限已加载 */
    permsReady: function () { return loadPermissions(); },
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
  async function uploadOne(file) {
    if (!file) return;
    const oversize = sizeError(file);
    if (oversize) { uploadMsg('✗ ' + file.name + '：' + oversize, 'err'); return; }
    uploadMsg('正在解析 ' + file.name + '（' + fmtSize(file.size) + '）…');
    const fd = new FormData(); fd.append('file', file);
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
    const started = Date.now();
    const r = await fetch('/api/docs/upload', { method: 'POST', body: fd });
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
    // 标题上先说清楚，免得有人盯着进度不敢关
    const titleEl = document.querySelector('#appModal .modal-title');
    if (titleEl) titleEl.textContent = '上传中（可关闭窗口，后台会继续）';
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
      dt.innerHTML = '✓ 完成：成功 <b>' + done + '</b> 个' + (fail ? '，失败 <b style="color:var(--danger)">' + fail + '</b> 个（鼠标悬停可看原因）' : '') +
        '。文档已入库，现在可以提问了。';
      if (okBtn) { okBtn.disabled = false; okBtn.textContent = '完成'; }
      if (cancelBtn) cancelBtn.disabled = false;
      const t2 = document.querySelector('#appModal .modal-title');
      if (t2) t2.textContent = '选择要上传的文件';
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
    const dz = document.getElementById('dropzone');
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
    const fi = document.getElementById('fileInput');
    if (fi) fi.onchange = e => { uploadFiles(e.target.files); e.target.value = ''; };
    // 选文件夹：先列清单，用户勾掉不要的再传
    const folder = document.getElementById('folderInput');
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
    openModal({
      title: '退出登录', danger: true, okText: '确认退出', cancelText: '取消',
      body: '<p style="margin:0 0 8px">退出后需要重新输入账号密码才能继续使用。</p>' +
        '<p style="margin:0;color:var(--muted);font-size:12px">未发送的草稿保存在当前浏览器，下次用同一账号登录仍可找回；会话记录已存到服务端，不会丢。</p>',
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
      : NAV.filter(n => !n.groups.length || isAdmin || n.groups.some(g => groups.indexOf(g) >= 0));
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
        '<div class="upload-area open" id="uploadArea">' +
          '<div class="upload-title"><span class="t">快速上传</span><span class="upload-hint">拖入即可入库</span></div>' +
          '<div class="upload-body">' +
            '<div class="dropzone" id="dropzone"><div class="drop-icon">⇪</div>' +
              '<div class="drop-text">点击或拖拽上传文档</div>' +
              '<div class="drop-formats" id="dropFormats">支持多格式</div></div>' +
            '<div class="upload-msg" id="uploadMsg" style="display:none"></div>' +
            '<button class="folder-btn" onclick="Shell.pickFolder()">📁 选择文件夹上传</button>' +
            '<input type="file" id="fileInput" hidden>' +
            '<input type="file" id="folderInput" webkitdirectory multiple hidden>' +
          '</div>' +
        '</div>' +
        '<div class="user-block" id="sidebarUserBlock"></div>' +
        '<div class="sidebar-footer">水利知识库 · 让知识更好用</div>' +
      '</aside>';
    host.insertBefore(aside.firstElementChild, host.firstChild);

    // 暴露给内联 onclick 使用
    global.Shell.newSession = newSession;
    renderSessions();
    renderUser();
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

  // ---------- 权限码（前端只控制入口显示，真正拦截在服务端） ----------
  let ownedPermissions = new Set();
  function hasPermission(code) {
    return ownedPermissions.has(code);
  }
  // 权限清单的加载 promise：功能页守卫要等它回来再判定，否则拿空集合会把有权限的人也拦住
  let permsReady = null;
  async function loadPermissions() {
    if (permsReady) return permsReady;
    permsReady = (async function () {
      try {
        const r = await fetch('/api/auth/permissions');
        if (!r.ok) return;
        const j = await r.json();
        ownedPermissions = new Set(j.permissions || []);
        renderAdminEntry();
      } catch (e) { /* 权限接口失败时保持默认不显示，默认拒绝 */ }
    })();
    return permsReady;
  }
  // 右上角管理后台入口：仅拥有 admin:access 才显示，免登录跳转（同源共享会话令牌）
  function renderAdminEntry() {
    const slot = document.getElementById('adminEntrySlot');
    if (!slot) return;
    const allowed = hasPermission('admin:access') ||
      (function () { const u = global.WrkAuth && global.WrkAuth.getUser(); return !!(u && (u.groups || []).indexOf('admin') >= 0); })();
    slot.innerHTML = allowed
      ? '<a class="text-btn admin-entry" href="admin.html" title="进入管理后台">管理后台 ›</a>'
      : '';
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
    el.textContent = msg || '操作失败';
    el.classList.add('show');
    const box = el.closest ? el.closest('.modal-box') : null;
    if (box) box.scrollTop = 0;
  }
  function clearModalError() {
    const el = document.getElementById('modalAlert');
    if (el) { el.classList.remove('show'); el.textContent = ''; }
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
        '<div class="modal-head' + (opts.danger ? ' danger' : '') + '"><div class="modal-title">' +
          (opts.danger ? '<span style="margin-right:6px">⚠</span>' : '') + esc(opts.title || '提示') + '</div>' +
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
    'models.html': { label: '模型配置', parent: 'settings.html', parentLabel: '设置' },
    'governance.html': { label: '权限与日志', parent: 'settings.html', parentLabel: '设置' },
    'profile.html': { label: '个人中心', back: 'index.html', backText: '返回问答' },
    'manage.html': { label: '知识库文档', back: 'index.html', backText: '返回问答' }
  };
  function renderTopbarNav() {
    const file = location.pathname.split('/').pop() || 'index.html';
    const meta = PAGE_NAV[file];
    const host = document.querySelector('.topbar-left');
    if (!meta || !host || host.querySelector('.topbar-nav-left')) return;
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

  global.initShell = function (active) {
    // 未登录直接敲静态页地址也要弹回登录页（静态资源是后端白名单，前端必须自查）
    if (global.WrkAuth && !global.WrkAuth.guardPage()) return;
    renderSidebar(active);
    renderTopbarNav();
    injectAdminHealth();
    checkHealth();
    renderAdminEntry();
    loadPermissions();
    // 组织名先按内置兜底渲染，拿到服务端字典后重绘侧栏用户块（组织名可能已被管理员改过）
    loadOrgLabels().then(ok => { if (ok) renderUser(); });
    // 会话清单与权限并行拉取：先渲染本地缓存，网络回来后再合并
    syncSessions();
    if (global.setInterval) setInterval(checkHealth, 30000);
  };
  global.appToast = toast;
})(window);
