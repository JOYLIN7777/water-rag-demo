/* ===================================================================
   水利知识库 · 前端认证公共脚本
   负责：token 存取(sessionStorage)、全局 fetch 自动附带 Bearer、
        401 自动刷新一次、刷新失败跳登录、登录态回显。
   业务页在 app.js 之前引入本文件即可，原有 fetch 调用无需改动；
   login.html 不引入本文件。
   =================================================================== */
(function (global) {
  const KEY_ACCESS = 'wrk_access_token';
  const KEY_REFRESH = 'wrk_refresh_token';
  const KEY_USER = 'wrk_user_info';

  // 保存原生 fetch，供本脚本自身的 refresh/logout 使用，避免被补丁递归拦截
  const rawFetch = global.fetch.bind(global);

  function getAccess() { try { return sessionStorage.getItem(KEY_ACCESS) || ''; } catch (e) { return ''; } }
  function getRefresh() { try { return sessionStorage.getItem(KEY_REFRESH) || ''; } catch (e) { return ''; } }
  function getUser() { try { return JSON.parse(sessionStorage.getItem(KEY_USER) || 'null'); } catch (e) { return null; } }

  function saveSession(data) {
    try {
      if (data.accessToken) sessionStorage.setItem(KEY_ACCESS, data.accessToken);
      if (data.refreshToken) sessionStorage.setItem(KEY_REFRESH, data.refreshToken);
      if (data.user) sessionStorage.setItem(KEY_USER, JSON.stringify({
        user: data.user, displayName: data.displayName, groups: data.groups, tenant: data.tenant
      }));
    } catch (e) {}
  }

  function clearSession() {
    try {
      sessionStorage.removeItem(KEY_ACCESS);
      sessionStorage.removeItem(KEY_REFRESH);
      sessionStorage.removeItem(KEY_USER);
    } catch (e) {}
  }

  function goLogin() {
    clearSession();
    if (location.pathname.indexOf('login.html') < 0) {
      location.href = 'login.html?redirect=' + encodeURIComponent(location.pathname + location.search);
    }
  }

  // 用 refresh token 换新的 access/refresh；成功返回 true
  async function doRefresh() {
    const rt = getRefresh();
    if (!rt) return false;
    try {
      const r = await rawFetch('/api/auth/refresh', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: rt })
      });
      if (!r.ok) return false;
      saveSession(await r.json());
      return true;
    } catch (e) { return false; }
  }

  let refreshing = null; // 并发 401 共用一次刷新，避免刷新风暴
  function ensureFresh() {
    if (!refreshing) refreshing = doRefresh().finally(() => { refreshing = null; });
    return refreshing;
  }

  /** 是否是需要带 token 的 API 路径（静态资源/登录接口本身跳过） */
  function needAuth(url) {
    const u = String(url || '');
    return u.indexOf('/api/') === 0
        && u.indexOf('/api/auth/login') < 0
        && u.indexOf('/api/auth/refresh') < 0;
  }

  // 全局 fetch 补丁：透明地给 API 请求加 Bearer，401 刷新一次并重试
  global.fetch = async function (url, options, _retried) {
    options = options || {};
    let headers = new Headers(options.headers || {});
    if (needAuth(url)) {
      const token = getAccess();
      if (token) headers.set('Authorization', 'Bearer ' + token);
    }
    const r = await rawFetch(url, Object.assign({}, options, { headers: headers }));
    if (r.status === 401 && needAuth(url) && !_retried && getRefresh()) {
      if (await ensureFresh()) {
        // 重试时用新 token 覆盖 Authorization
        const h2 = new Headers(options.headers || {});
        h2.set('Authorization', 'Bearer ' + getAccess());
        return rawFetch(url, Object.assign({}, options, { headers: h2 }));
      }
      goLogin();
    }
    return r;
  };

  /** 登出：通知后端拉黑 token，再清本地跳转 */
  async function logout() {
    try {
      const token = getAccess();
      await rawFetch('/api/auth/logout', {
        method: 'POST', headers: token ? { 'Authorization': 'Bearer ' + token } : {}
      });
    } catch (e) {}
    goLogin();
  }

  function isLoggedIn() { return !!(getAccess() || getRefresh()); }

  /**
   * 当前账号的数据命名空间。会话列表、消息缓存、草稿等 localStorage/sessionStorage
   * 都必须带这个前缀——否则同一浏览器切换账号会直接看到上一个账号的会话。
   */
  function scope() {
    const u = getUser();
    return u && u.user ? String(u.user) : 'demo';
  }

  /**
   * 页面级登录守卫：静态页是后端白名单，直接敲 URL 也能打开，
   * 因此前端必须在渲染前检查登录态——只有 access/refresh 都在才放行。
   * 退出登录会清掉两者，之后直接访问 /manage.html 会被弹回登录页。
   */
  function guardPage() {
    var publicPages = ['login.html'];
    var path = location.pathname;
    if (publicPages.some(function (p) { return path.indexOf(p) >= 0; })) return true;
    if (!isLoggedIn()) { goLogin(); return false; }
    return true;
  }

  global.WrkAuth = {
    getAccess, getRefresh, getUser, saveSession, clearSession, scope,
    doRefresh, logout, goLogin, isLoggedIn, guardPage, rawFetch
  };
})(window);
