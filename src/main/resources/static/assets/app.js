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
  const SETTINGS = [
    { key: 'search', href: 'search.html', label: '检索实验', icon: 'search',
      desc: '并排对比 BM25 全文检索、kNN 向量检索与 RRF 融合结果', groups: [] },
    { key: 'models', href: 'models.html', label: '模型配置', icon: 'model',
      desc: '配置对话 / 向量模型，切换公有云 API 或内网私有化模型', groups: ['admin'] },
    { key: 'tree', href: 'tree.html', label: '会话树', icon: 'tree',
      desc: '查看消息分支，从任意节点 fork 开新支或 rewind 回退', groups: [] },
    { key: 'governance', href: 'governance.html', label: '企业治理', icon: 'shield',
      desc: '查看登录身份、三层权限矩阵与全量审计日志', groups: ['admin'] }
  ];
  global.ShellSettings = SETTINGS;

  const svg = name => '<svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">' + (ICONS[name] || '') + '</svg>';
  const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  // ---------- 会话数据（与首页同源 localStorage）----------
  function loadSessions() {
    try { return JSON.parse(global.localStorage.getItem('water-rag-sessions') || '[]'); }
    catch (e) { return []; }
  }
  function saveSessions(list) {
    global.localStorage.setItem('water-rag-sessions', JSON.stringify(list));
  }
  function fmtTime(ts) {
    if (!ts) return '';
    const d = new Date(ts), now = new Date();
    if (isNaN(d.getTime())) return '';
    const hm = String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
    if (d.toDateString() === now.toDateString()) return '今天 ' + hm;
    const y = new Date(now); y.setDate(y.getDate() - 1);
    if (d.toDateString() === y.toDateString()) return '昨天 ' + hm;
    return (d.getMonth() + 1) + '/' + d.getDate() + ' ' + hm;
  }

  // 会话切换回调（首页注册，功能页默认跳回首页定位）
  let sessionChangeHandler = null;
  let currentSessionId = '';
  global.Shell = {
    setSession(id) { currentSessionId = id; renderSessions(); },
    onSessionChange(fn) { sessionChangeHandler = fn; },
    getSessions: loadSessions,
    saveSessions,
    refreshDocs() { return loadDocs(); },
    getCurrentUser() { const a = global.WrkAuth; return a ? a.getUser() : null; }
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
  function removeSession(id, ev) {
    ev.stopPropagation();
    if (!global.confirm('确定删除该会话的本地记录吗？')) return;
    let list = loadSessions().filter(s => s.id !== id);
    saveSessions(list);
    if (currentSessionId === id) {
      if (list.length) pickSession(list[0].id);
      else newSession();
    } else renderSessions();
  }

  function renderSessions() {
    const el = document.getElementById('sessionList');
    if (!el) return;
    const list = loadSessions().slice().sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
    if (!list.length) { el.innerHTML = '<div class="session-empty">暂无会话，点击上方新建</div>'; return; }
    el.innerHTML = list.map(s => {
      const short = String(s.id || '').startsWith('session-') ? s.id.slice(8, 14) : String(s.id || '').slice(0, 8);
      return '<div class="session-item' + (s.id === currentSessionId ? ' active' : '') + '" data-sid="' + esc(s.id) + '">' +
        '<span class="session-dot"></span><span class="session-body">' +
        '<span class="session-name">' + esc(s.title || ('会话 ' + short)) + '</span>' +
        '<span class="session-time">' + esc(fmtTime(s.updatedAt)) + '</span></span>' +
        '<button class="session-delete" title="删除本地会话">×</button></div>';
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
  async function uploadFiles(all) {
    const files = Array.from(all || []).filter(f => !isJunkFile(f.name));
    if (!files.length) { uploadMsg('✗ 没有可上传的文件', 'err'); return; }
    if (!supportedExts.size) {
      try { const d = await (await fetch('/api/docs/formats')).json(); supportedExts = new Set(d.extensions); } catch (e) {}
    }
    const valid = files.filter(f => {
      const i = f.name.lastIndexOf('.');
      return i >= 0 && supportedExts.has(f.name.slice(i + 1).toLowerCase());
    });
    if (!valid.length) { uploadMsg('✗ 没有支持的文档格式（共 ' + files.length + ' 个文件跳过）', 'err'); return; }
    const area = document.getElementById('uploadArea'); if (area) area.classList.add('open');
    for (let i = 0; i < valid.length; i++) {
      const f = valid[i];
      uploadMsg('[' + (i + 1) + '/' + valid.length + '] 正在解析 ' + f.name + '…');
      const oversize = sizeError(f);
      if (oversize) { uploadMsg('[' + (i + 1) + '/' + valid.length + '] ✗ ' + f.name + '：' + oversize, 'err'); continue; }
      const fd = new FormData(); fd.append('file', f);
      try {
        const r = await fetch('/api/docs/upload', { method: 'POST', body: fd });
        const j = await r.json();
        if (!r.ok || j.chunks === undefined) throw new Error(j.message || ('上传失败（HTTP ' + r.status + '）'));
        uploadMsg('[' + (i + 1) + '/' + valid.length + '] ✓ ' + f.name + '（' + j.chunks + ' 切片）');
      } catch (e) { uploadMsg('[' + (i + 1) + '/' + valid.length + '] ✗ ' + f.name + '：' + e.message, 'err'); }
    }
    uploadMsg('✓ 批量上传处理完成，共 ' + valid.length + ' 个文件', 'ok');
    toast('文档已入库', 'ok');
    loadDocs();
  }

  const DOC_LIMIT = 2;
  async function loadDocs() {
    const el = document.getElementById('docList');
    if (!el) return;
    try {
      const all = await (await fetch('/api/docs/list')).json();
      const totalChunks = all.reduce((s, d) => s + Number(d.chunks || 0), 0);
      const badge = document.getElementById('uploadBadge');
      if (badge) badge.textContent = all.length + ' 个 · ' + totalChunks + ' 片';
      const list = all.slice().sort((a, b) => String(b.uploadedAt || '').localeCompare(String(a.uploadedAt || '')));
      if (!list.length) { el.innerHTML = '<div class="doc-empty">知识库暂无文档</div>'; return; }
      el.innerHTML = list.slice(0, DOC_LIMIT).map(d =>
        '<div class="doc-item"><span class="doc-name" title="' + esc(d.fileName) + '">' + esc(d.fileName) + '</span>' +
        '<span class="doc-meta">' + d.chunks + '片</span><button class="doc-delete" title="删除文档">×</button></div>').join('') +
        (list.length > DOC_LIMIT ? '<a class="doc-more" href="manage.html">查看全部 ' + list.length + ' 个文档 →</a>' : '');
      el.querySelectorAll('.doc-item').forEach((item, idx) => {
        const d = list[idx];
        item.querySelector('.doc-delete').onclick = () => {
          if (!global.confirm('确定删除「' + d.fileName + '」（' + d.chunks + ' 个切片）吗？')) return;
          fetch('/api/docs?fileName=' + encodeURIComponent(d.fileName), { method: 'DELETE' })
            .then(r => r.json()).then(j => {
              if (j.deletedChunks === undefined) throw new Error(j.message || '删除失败');
              uploadMsg('✓ 已删除「' + d.fileName + '」（' + j.deletedChunks + ' 个切片）', 'ok');
              loadDocs();
            }).catch(e => uploadMsg('✗ 删除失败：' + e.message, 'err'));
        };
      });
    } catch (e) { el.innerHTML = '<div class="doc-empty">文档列表加载失败</div>'; }
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
        // 拖的是文件夹：递归收集所有文件再批量入库
        if (items && items.length && items[0] && items[0].webkitGetAsEntry) {
          const files = [];
          (async () => {
            for (const item of items) {
              const entry = item.webkitGetAsEntry();
              if (entry) await walkEntry(entry, files);
            }
            if (files.length) uploadFiles(files);
          })();
        } else {
          uploadFiles(e.dataTransfer.files);
        }
      };
    }
    const fi = document.getElementById('fileInput');
    if (fi) fi.onchange = e => { uploadFiles(e.target.files); e.target.value = ''; };
    const folder = document.getElementById('folderInput');
    if (folder) folder.onchange = e => { uploadFiles(e.target.files); e.target.value = ''; };
    const toggle = document.querySelector('.upload-toggle');
    if (toggle) toggle.onclick = () => document.getElementById('uploadArea').classList.toggle('open');
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
      '<a class="user-avatar" href="profile.html" title="个人中心">' + esc((name || '演').slice(0, 1).toUpperCase()) + '</a>' +
      '<a class="user-meta" href="profile.html" title="个人中心">' +
        '<div class="user-name">' + esc(name) + '</div>' +
        '<div class="user-tenant">租户 ' + esc(tenant) + '</div></a>' +
      '<button class="user-logout" title="退出登录" onclick="WrkAuth.logout()">⏻</button>';
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
        '<div class="brand"><div class="brand-mark">水</div>' +
          '<div><div class="brand-title">水利知识库</div><div class="brand-sub">RAG 智能问答系统</div></div></div>' +
        '<div class="section-label" style="margin-top:0">工作台</div>' +
        '<nav class="nav-list">' + items + '</nav>' +
        '<button class="new-chat" onclick="Shell.newSession()">＋ 新建会话</button>' +
        '<div class="section-label">历史会话</div>' +
        '<div class="session-list" id="sessionList"></div>' +
        '<div class="upload-area" id="uploadArea">' +
          '<button class="upload-toggle"><span class="caret">▶</span><span class="t">知识库文档</span><span id="uploadBadge">—</span></button>' +
          '<div class="upload-body">' +
            '<div class="dropzone" id="dropzone"><div class="drop-icon">⇪</div>' +
              '<div class="drop-text">点击或拖拽上传文档</div>' +
              '<div class="drop-formats" id="dropFormats">支持多格式</div></div>' +
            '<div class="upload-msg" id="uploadMsg" style="display:none"></div>' +
            '<button class="folder-btn" onclick="document.getElementById(\'folderInput\').click()">📁 选择文件夹上传</button>' +
            '<div class="doc-list" id="docList"></div>' +
            '<input type="file" id="fileInput" hidden>' +
            '<input type="file" id="folderInput" webkitdirectory multiple hidden>' +
          '</div>' +
        '</div>' +
        '<a class="settings-entry' + (active === 'settings' ? ' active' : '') + '" href="settings.html" title="设置">' +
          svg('settings') + '<span>设置</span><span class="settings-arrow">›</span></a>' +
        '<div class="user-block" id="sidebarUserBlock"></div>' +
        '<div class="sidebar-footer">Spring Boot 3.4 · Spring AI<br>Elasticsearch</div>' +
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

  // ---------- 服务探活 ----------
  async function checkHealth() {
    const pills = document.querySelectorAll('[data-status-pill]');
    if (!pills.length) return;
    let healthy = false;
    try { const j = await (await fetch('/api/search/health')).json(); healthy = !!j.healthy; }
    catch (e) { healthy = false; }
    pills.forEach(p => {
      p.className = 'status-pill' + (healthy ? '' : ' off');
      p.innerHTML = '<span class="status-dot"></span>' + (healthy ? '服务正常' : '服务不可用');
    });
  }

  function toast(msg, type) {
    let t = document.getElementById('appToast');
    if (!t) { t = document.createElement('div'); t.id = 'appToast'; t.className = 'toast'; document.body.appendChild(t); }
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
