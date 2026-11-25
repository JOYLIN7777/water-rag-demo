(function (global) {
  // 登录/密码类接口的公钥加密工具。仅在 localhost/HTTPS 下可用（Web Crypto 安全上下文要求）。
  // 缓存 CryptoKey 但每次校验服务端公钥指纹：服务重启换了密钥要立刻失效，
  // 否则用旧公钥加密的密码服务端解不开，表现为"密码正确却登录失败"。
  let cachedKey = null;
  let cachedFingerprint = '';

  function toBytes(b64) {
    const raw = atob(b64);
    const out = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
    return out;
  }

  async function publicKey() {
    const r = await fetch('/api/auth/public-key');
    if (!r.ok) throw new Error('无法获取加密公钥');
    const j = await r.json();
    if (cachedKey && cachedFingerprint === j.publicKey) return cachedKey;
    cachedKey = await crypto.subtle.importKey(
      'spki', toBytes(j.publicKey),
      { name: 'RSA-OAEP', hash: 'SHA-256' }, false, ['encrypt']);
    cachedFingerprint = j.publicKey;
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
