/**
 * @fileoverview UI 组件库的 ES 模块入口。
 *
 * 实现在 ui.js（UMD 风格 IIFE，挂 window.UI，供传统 <script> 同步加载）。
 * 本文件供新代码用 import 消费，从全局取实现并 re-export——保证只有一份实现。
 *
 * 用法（新代码，推荐）：
 *   import { createButton, createTable } from './ui.module.js';
 *   // 前提：页面已 <script src="assets/ui.js"></script>
 *
 * 用法（存量页面）：
 *   <script src="assets/ui.js"></script>
 *   UI.createButton({ ... })
 */

const impl = globalThis.UI;
if (!impl) {
  throw new Error('[UI] 请先加载 assets/ui.js（<script src="assets/ui.js"></script>）');
}

export const {
  el, uid,
  createButton, createSelect, createInput, createField, createTag,
  createEmptyRow, createTable, createPager,
  createToast, createConfirm, createAvatar
} = impl;

export const UI = impl;
export default impl;
