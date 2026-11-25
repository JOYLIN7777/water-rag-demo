/**
 * @fileoverview 企业级 UI 组件库（零依赖，原生 ES 模块）。
 *
 * 设计原则：
 * 1. 所有 create* 组件返回 DOM 节点，不返回 HTML 字符串，不碰业务 DOM；
 * 2. 输入用 textContent，禁止 innerHTML 拼接用户数据，天然防 XSS；
 * 3. 完整 JSDoc + 参数校验 + 可访问性（aria / label 关联）；
 * 4. 复用 app.css 已有类名，组件层不重复定义样式；
 * 5. 无框架依赖，静态页用 <script type="module"> 引入后按需 import。
 *
 * 用法：
 *   <script type="module">
 *     import { createButton, createSelect, createField } from './assets/ui.js';
 *     document.body.append(createField({ label: '类型', control: createSelect({ ... }) }));
 *   </script>
 */

// ============ 内部工具 ============

/** 创建元素并设置属性；文本一律走 textContent，杜绝 XSS */
function el(tag, opts = {}, children = []) {
  if (typeof tag !== 'string' || !tag) throw new TypeError('[UI] el: tag 必须是非空字符串');
  const node = document.createElement(tag);
  if (opts.className) node.className = String(opts.className);
  if (opts.text !== undefined && opts.text !== null) node.textContent = String(opts.text);
  if (opts.id) node.id = String(opts.id);
  if (opts.title) node.title = String(opts.title);
  if (opts.type && tag === 'button') node.type = String(opts.type);
  if (opts.disabled) node.setAttribute('disabled', '');
  if (opts.attrs && typeof opts.attrs === 'object') {
    for (const [k, v] of Object.entries(opts.attrs)) {
      if (v === undefined || v === null || v === false) continue;
      node.setAttribute(k, v === true ? '' : String(v));
    }
  }
  if (opts.dataset && typeof opts.dataset === 'object') {
    for (const [k, v] of Object.entries(opts.dataset)) {
      if (v !== undefined && v !== null) node.dataset[k] = String(v);
    }
  }
  if (opts.on && typeof opts.on === 'object') {
    for (const [evt, fn] of Object.entries(opts.on)) {
      if (typeof fn === 'function') node.addEventListener(evt, fn);
    }
  }
  for (const child of [].concat(children)) {
    if (child === undefined || child === null || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

/** 生成稳定唯一 id，用于 label/control 关联与 aria 引用 */
let _seq = 0;
function uid(prefix = 'ui') {
  _seq += 1;
  return prefix + '-' + Date.now().toString(36) + '-' + _seq.toString(36);
}

/** 权限判定：注入的 hasPermission 或读取 Shell（兼容旧运行时） */
function allowed(perm) {
  if (!perm) return true;
  try {
    if (typeof globalThis.Shell?.hasPermission === 'function') {
      return globalThis.Shell.hasPermission(perm);
    }
  } catch (_) { /* 权限未就绪时按可见处理，由业务层守卫兜底 */ }
  return true;
}

// ============ 基础控件 ============

/**
 * 创建按钮。
 * @param {object} o
 * @param {string} o.label 按钮文字
 * @param {'default'|'primary'|'danger'|'green'|'amber'} [o.variant] 样式变体
 * @param {'sm'|'md'} [o.size] 尺寸，默认 md
 * @param {string} [o.icon] 前置图标字符
 * @param {Function} [o.onClick] 点击事件
 * @param {boolean} [o.disabled] 是否禁用
 * @param {string} [o.perm] 权限码，无权限时返回空节点（不显示）
 * @param {string} [o.title] 原生 tooltip
 * @returns {HTMLButtonElement|Text} DOM 节点；无权限时返回空文本节点
 */
export function createButton(o = {}) {
  if (!o || typeof o.label !== 'string') throw new TypeError('[UI] createButton: label 必填');
  if (!allowed(o.perm)) return document.createTextNode('');
  const cls = ['btn'];
  if (o.variant && o.variant !== 'default') cls.push('btn-' + o.variant);
  if (o.size === 'sm' || o.size === 'md') cls.push('btn-' + o.size);
  const kids = [];
  if (o.icon) kids.push(el('span', { className: 'ic', text: o.icon }));
  kids.push(document.createTextNode(o.label));
  return el('button', {
    className: cls.join(' '), type: o.type || 'button', title: o.title, disabled: o.disabled,
    attrs: { 'aria-label': o.label }, on: { click: o.onClick }
  }, kids);
}

/**
 * 创建下拉框（含 label 包装时请用 createField）。
 * @param {object} o
 * @param {string} [o.id] 控件 id，缺省自动生成
 * @param {Array<{value:string,label:string,disabled?:boolean}>} o.options 选项
 * @param {string|number} [o.value] 当前值
 * @param {string} [o.placeholder] 占位选项文案（value 为空）
 * @param {Function} [o.onChange] change 事件，回调参数为当前值
 * @param {boolean} [o.disabled] 是否禁用
 * @returns {HTMLSelectElement}
 */
export function createSelect(o = {}) {
  if (!Array.isArray(o.options)) throw new TypeError('[UI] createSelect: options 必须是数组');
  const select = el('select', {
    id: o.id || uid('select'), disabled: o.disabled,
    attrs: { 'aria-label': o.ariaLabel || '下拉选择' }
  });
  if (o.placeholder !== undefined) {
    select.append(el('option', { text: o.placeholder, attrs: { value: '' } }));
  }
  for (const opt of o.options) {
    if (!opt || opt.value === undefined) continue;
    const node = el('option', {
      text: opt.label !== undefined ? opt.label : String(opt.value),
      attrs: { value: String(opt.value), disabled: opt.disabled ? '' : null }
    });
    if (String(opt.value) === String(o.value)) node.selected = true;
    select.append(node);
  }
  if (typeof o.onChange === 'function') {
    select.addEventListener('change', () => o.onChange(select.value));
  }
  return select;
}

/**
 * 创建单行输入框。
 * @param {object} o
 * @param {string} [o.id] 控件 id
 * @param {'text'|'password'|'number'} [o.type] 输入框类型，默认 text
 * @param {string} [o.value] 初始值
 * @param {string} [o.placeholder] 占位文案
 * @param {boolean} [o.disabled] 是否禁用
 * @param {Function} [o.onInput] input 事件
 * @returns {HTMLInputElement}
 */
export function createInput(o = {}) {
  const input = el('input', {
    id: o.id || uid('input'), type: o.type || 'text',
    attrs: {
      value: o.value !== undefined ? String(o.value) : null,
      placeholder: o.placeholder || null,
      'aria-label': o.ariaLabel || '文本输入',
      autocomplete: o.autocomplete || 'off'
    }
  });
  if (o.disabled) input.disabled = true;
  if (typeof o.onInput === 'function') input.addEventListener('input', e => o.onInput(e.target.value));
  return input;
}

/**
 * 创建表单项（label + 控件 + 提示），label 与控件用 for/id 正确关联。
 * @param {object} o
 * @param {string} o.label 字段标题
 * @param {Node} o.control 控件节点（createInput/createSelect 等）
 * @param {string} [o.hint] 辅助说明
 * @param {string} [o.className] 附加 class
 * @returns {HTMLDivElement}
 */
export function createField(o = {}) {
  if (!o || !o.control || !(o.control instanceof Node)) {
    throw new TypeError('[UI] createField: control 必须是 DOM 节点');
  }
  const wrap = el('div', { className: 'field' + (o.className ? ' ' + o.className : '') });
  const label = el('label', { text: o.label || '' });
  const control = o.control;
  // 控件没有 id 时补一个，保证 label for 关联有效
  if (!control.id) control.id = uid('control');
  label.htmlFor = control.id;
  wrap.append(label, control);
  if (o.hint) wrap.append(el('div', { className: 'form-hint', text: o.hint }));
  return wrap;
}

/**
 * 创建状态标签。
 * @param {object|string} o 字符串时视为 text；对象见下
 * @param {string} o.text 标签文字
 * @param {'default'|'ok'|'warn'|'no'|'info'|'emb'} [o.variant] 样式
 * @returns {HTMLSpanElement}
 */
export function createTag(o = {}) {
  const opt = typeof o === 'string' ? { text: o } : o;
  const cls = ['tag'];
  if (opt.variant && opt.variant !== 'default') cls.push(opt.variant);
  return el('span', { className: cls.join(' '), text: opt.text || '' });
}

// ============ 数据展示 ============

/**
 * 创建空态行（用于 <tbody>）。
 * @param {object|string} o 字符串时视为 text
 * @param {string} o.text 空态文案
 * @param {number} [o.colspan] 跨列数，默认 1
 * @returns {HTMLTableRowElement}
 */
export function createEmptyRow(o = {}) {
  const opt = typeof o === 'string' ? { text: o } : o;
  const td = el('td', {
    className: 'empty' + (opt.inline ? ' empty-inline' : ''),
    text: opt.text || '暂无数据',
    attrs: { colspan: String(opt.colspan || 1) }
  });
  return el('tr', {}, td);
}

/**
 * 创建数据表格。列定义与行数据分离，行渲染由 renderRow 回调负责（返回 <tr> 节点）。
 * @param {object} o
 * @param {Array<{title:string,className?:string}>} o.columns 列定义
 * @param {Array<*>} o.rows 数据行
 * @param {Function} o.renderRow (row, index) => HTMLTableRowElement
 * @param {string} [o.emptyText] 空数据时展示的文案
 * @param {string} [o.className] 附加 class
 * @returns {HTMLTableElement}
 */
export function createTable(o = {}) {
  if (!Array.isArray(o.columns)) {
    throw new TypeError('[UI] createTable: columns 必须是数组');
  }
  if (o.rows && o.rows.length && typeof o.renderRow !== 'function') {
    throw new TypeError('[UI] createTable: 有数据时 renderRow 函数必填');
  }
  const table = el('table', { className: 'data-table' + (o.className ? ' ' + o.className : '') });
  const thead = el('thead');
  const headRow = el('tr');
  for (const col of o.columns) {
    headRow.append(el('th', { className: col.className || '', text: col.title }));
  }
  thead.append(headRow);
  const tbody = el('tbody');
  if (!o.rows || !o.rows.length) {
    tbody.append(createEmptyRow({ text: o.emptyText || '暂无数据', colspan: o.columns.length }));
  } else {
    o.rows.forEach((row, i) => {
      const tr = o.renderRow(row, i);
      if (!(tr instanceof Node)) throw new TypeError('[UI] createTable: renderRow 必须返回 DOM 节点');
      tbody.append(tr);
    });
  }
  table.append(thead, tbody);
  return table;
}

/**
 * 创建分页器（页码 + 上下页 + 跳转）。
 * @param {object} o
 * @param {number} o.page 当前页（1 起）
 * @param {number} o.size 每页条数
 * @param {number} o.total 总条数
 * @param {Function} o.onGo 页码变化回调 (page) => void
 * @returns {HTMLElement}
 */
export function createPager(o = {}) {
  const page = Math.max(1, Number(o.page) || 1);
  const size = Math.max(1, Number(o.size) || 10);
  const total = Math.max(0, Number(o.total) || 0);
  const pages = Math.max(1, Math.ceil(total / size));
  const root = el('div', { className: 'pager', attrs: { role: 'navigation', 'aria-label': '分页导航' } });
  const go = typeof o.onGo === 'function' ? o.onGo : () => {};
  const navBtn = (label, target, disabled, aria) => el('button', {
    className: 'pg-btn', type: 'button', text: label, disabled,
    attrs: { 'aria-label': aria }, on: { click: () => { if (!disabled) go(target); } }
  });
  root.append(navBtn('«', 1, page <= 1, '首页'));
  root.append(navBtn('‹', Math.max(1, page - 1), page <= 1, '上一页'));
  for (let p = 1; p <= pages; p++) {
    root.append(el('button', {
      className: 'pg-btn' + (p === page ? ' current' : ''), type: 'button', text: String(p),
      attrs: { 'aria-current': p === page ? 'page' : null }, on: { click: () => go(p) }
    }));
  }
  root.append(navBtn('›', Math.min(pages, page + 1), page >= pages, '下一页'));
  root.append(navBtn('»', pages, page >= pages, '末页'));
  return root;
}

// ============ 反馈类 ============

/**
 * 右下角轻提示（兼容旧 appToast 的视觉）。
 * @param {object|string} o 字符串时视为 message
 * @param {string} o.message 提示文案
 * @param {'ok'|'err'|'info'} [o.type] 类型
 * @param {number} [o.duration] 存活毫秒，默认 2600
 * @returns {HTMLElement} 提示节点
 */
export function createToast(o = {}) {
  const opt = typeof o === 'string' ? { message: o } : o;
  let node = document.getElementById('appToast');
  if (!node) {
    node = el('div', { id: 'appToast', className: 'toast', attrs: { role: 'status', 'aria-live': 'polite' } });
    document.body.append(node);
  }
  node.textContent = opt.message || '';
  node.className = 'toast show' + (opt.type ? ' ' + opt.type : '');
  clearTimeout(node._timer);
  node._timer = setTimeout(() => node.classList.remove('show'), Number(opt.duration) || 2600);
  return node;
}

/**
 * 确认对话框（Promise 化，不阻塞）。
 * @param {object|string} o 字符串时视为 message
 * @param {string} o.message 确认文案
 * @param {string} [o.okText] 确认按钮文案，默认“确定”
 * @param {string} [o.cancelText] 取消按钮文案，默认“取消”
 * @returns {Promise<boolean>}
 */
export function createConfirm(o = {}) {
  const opt = typeof o === 'string' ? { message: o } : o;
  return new Promise(resolve => {
    const shell = globalThis.Shell;
    if (shell && typeof shell.modal === 'function') {
      shell.modal({
        title: '请确认', okText: opt.okText || '确定', cancelText: opt.cancelText === '' ? '' : (opt.cancelText || '取消'),
        body: el('p', { text: opt.message || '' }),
        onOk: close => { close(); resolve(true); },
        onCancel: () => resolve(false)
      });
      return;
    }
    resolve(typeof globalThis.confirm === 'function' ? globalThis.confirm(opt.message || '') : false);
  });
}

/**
 * 头像节点。
 * @param {object|string} o 字符串时视为 name
 * @param {string} o.name 显示名
 * @param {'sm'|'md'|'lg'} [o.size] 尺寸
 * @returns {HTMLElement}
 */
export function createAvatar(o = {}) {
  const opt = typeof o === 'string' ? { name: o } : o;
  const name = String(opt.name || '?');
  const initials = name.slice(0, 1).toUpperCase();
  return el('span', {
    className: 'avatar' + (opt.size ? ' avatar-' + opt.size : ''),
    text: initials, title: name,
    attrs: { role: 'img', 'aria-label': name }
  });
}

// ============ 统一导出 ============

export const UI = {
  el, uid,
  createButton, createSelect, createInput, createField, createTag,
  createEmptyRow, createTable, createPager,
  createToast, createConfirm, createAvatar
};

export default UI;
