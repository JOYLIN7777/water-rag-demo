/**
 * 权限树单一事实源。
 *
 * 规则（10 年前端视角）：
 * 1. 页面/菜单/按钮只在这里声明一次，name / 层级 / 路由 / 排序都在这里；
 * 2. data-perm 只用于运行时校验，不再各页零散上报"当前页面路径"；
 * 3. 管理员进入后台时整份清单上报，服务端对账：新增、改名、改路由、改层级、排序；
 * 4. 用户在菜单权限页手工新增的自定义节点（builtin=false 且不在清单里）一律保留；
 * 5. 清单中消失的内置节点标记为"已下线"而不是硬删，避免角色权限瞬间丢失。
 */
(function (global) {
  const MANIFEST = [
    { code: 'workbench:root', name: '工作台', type: 'directory', module: '工作台', order: 10, routePath: '', children: [
      { code: 'chat:use', name: '智能问答', type: 'menu', order: 11, routePath: '/index.html' },
      { code: 'doc:read', name: '知识库文档', type: 'menu', order: 12, routePath: '/manage.html', children: [
        { code: 'doc:write', name: '上传文档', type: 'btn', order: 121 },
        { code: 'doc:delete', name: '删除文档', type: 'btn', order: 122 },
        { code: 'doc:chunks', name: '查看分片', type: 'btn', order: 123 }
      ]},
      { code: 'search:use', name: '检索对比', type: 'menu', order: 13, routePath: '/search.html' },
      { code: 'tree:use', name: '会话结构', type: 'menu', order: 14, routePath: '/tree.html' }
    ]},
    { code: 'system:root', name: '管理后台', type: 'directory', module: '系统管理', order: 20, routePath: '/admin.html', children: [
      { code: 'admin:access', name: '进入管理后台', type: 'menu', order: 201, routePath: '/admin.html' },
      { code: 'console.config:root', name: '权限配置目录', type: 'directory', order: 21, children: [
        { code: 'user:read', name: '用户管理', type: 'menu', order: 211, routePath: '/users.html', children: [
          { code: 'user:write', name: '用户维护', type: 'btn', order: 2111 },
          { code: 'user:resetpwd', name: '重置密码', type: 'btn', order: 2112 },
          { code: 'user:resetcode', name: '复制重置码', type: 'btn', order: 2114 },
          { code: 'user:export', name: '导出用户', type: 'btn', order: 2113 }
        ]},
        { code: 'org:read', name: '组织架构', type: 'menu', order: 212, routePath: '/org.html' },
        { code: 'role:read', name: '角色定义', type: 'menu', order: 213, routePath: '/roles.html' },
        { code: 'perm:read', name: '菜单权限', type: 'menu', order: 214, routePath: '/perms.html' }
      ]},
      { code: 'console.biz:root', name: '业务管理', type: 'directory', order: 22, children: [
        { code: 'admin:docs', name: '文档管理', type: 'menu', order: 221, routePath: '/docs.html' },
        { code: 'model:read', name: '模型设置', type: 'menu', order: 222, routePath: '/models.html', children: [
          { code: 'model:write', name: '修改模型', type: 'btn', order: 2221 }
        ]},
        { code: 'usage:read', name: '用量统计', type: 'menu', order: 223, routePath: '/usage.html' }
      ]},
      { code: 'console.log:root', name: '日志审计', type: 'directory', order: 23, children: [
        { code: 'loginlog:read', name: '登录日志', type: 'menu', order: 231, routePath: '/loginlogs.html', children: [
          { code: 'loginlog:export', name: '导出登录日志', type: 'btn', order: 2311 }
        ]},
        { code: 'audit:read', name: '操作日志', type: 'menu', order: 232, routePath: '/audit.html', children: [
          { code: 'audit:export', name: '导出操作日志', type: 'btn', order: 2321 }
        ]}
      ]}
    ]}
  ];

  /** 摊平成上报数组，parentCode 由树结构自动生成，不靠散落的 PERM_PARENT 表 */
  function flatten() {
    const out = [];
    const walk = (nodes, parent) => nodes.forEach(n => {
      out.push({ code: n.code, name: n.name, type: n.type, module: n.module || '',
        parentCode: parent, routePath: n.routePath || '', orderNum: n.order || 0, builtin: true });
      if (n.children) walk(n.children, n.code);
    });
    walk(MANIFEST, '');
    return out;
  }

  global.PermManifest = { all: MANIFEST, flatten: flatten };
})(window);
