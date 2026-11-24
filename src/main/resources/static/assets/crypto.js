(function (global) {
  // 登录/密码类接口的公钥加密工具：公钥由服务端启动时生成，不落盘、不进 Git。
  // 仅在 localhost/HTTPS 下可用（Web Crypto 安全上下文要求），符合部署场景。
  let cachedKey = null;
  let cachedPromise = null;

  function toBytes(b64) {
    const raw = atob(b64);
    const out = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
    return out;
  }

  async function publicKey() {
    if (cachedKey) return cachedKey;
    if (!cachedPromise) {
      cachedPromise = (async function () {
        const r = await fetch('/api/auth/public-key');
        if (!r.ok) throw new Error('无法获取加密公钥');
        const j = await r.json();
        return await crypto.subtle.importKey(
          'spki', toBytes(j.publicKey),
          { name: 'RSA-OAEP', hash: 'SHA-256' }, false, ['encrypt']);
      })();
    }
    cachedKey = await cachedPromise;
    return cachedKey;
  }

  async function encrypt(value) {
    if (!value) return value;
    // 非 localhost/HTTPS 时 Web Crypto 不可用，退回明文以免登录完全失效（后端仍做 PBKDF2 校验）
    if (!global.crypto || !global.crypto.subtle) {
      console.warn('当前不是安全上下文，密码无法加密传输');
      return value;
    }
    const key = await publicKey();
    const buf = await crypto.subtle.encrypt(
      { name: 'RSA-OAEP' }, key, new TextEncoder().encode(value));
    let bin = '';
    new Uint8Array(buf).forEach(b => { bin += String.fromCharCode(b); });
    return btoa(bin);
  }

  global.PwdCrypto = { encrypt: encrypt };
})(window);
