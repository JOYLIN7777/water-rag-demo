package com.joylin.waterkb.security;

/**
 * 企业级 RBAC 权限码。
 *
 * <p>每个敏感接口都对应一个明确权限码，由 {@link RequirePermission} 注解声明，
 * {@link PermissionInterceptor} 在 Controller 执行前强制校验。
 * 前端只按权限码决定"显不显示入口"，真正的访问控制永远在服务端。</p>
 *
 * <p>角色与权限的映射集中在 {@link RolePermissions}；admin 组拥有全部权限，
 * 不存在"前端藏了按钮但接口仍可调用"的伪权限。</p>
 */
public final class Permission {

    public static final String ADMIN_ACCESS = "admin:access";   // 进入管理后台
    public static final String CHAT_USE = "chat:use";           // 智能问答
    public static final String DOC_READ = "doc:read";           // 查看文档
    public static final String DOC_WRITE = "doc:write";         // 上传文档
    public static final String DOC_DELETE = "doc:delete";       // 删除文档
    public static final String SEARCH_USE = "search:use";       // 检索对比
    public static final String TREE_USE = "tree:use";           // 问答分支
    public static final String MODEL_READ = "model:read";       // 查看模型配置
    public static final String MODEL_WRITE = "model:write";     // 修改模型配置
    public static final String AUDIT_READ = "audit:read";       // 查看审计日志
    public static final String USAGE_READ = "usage:read";       // 查看模型用量
    public static final String USER_READ = "user:read";         // 查看用户
    public static final String USER_WRITE = "user:write";       // 管理用户
    public static final String ORG_READ = "org:read";           // 组织架构（后台独立面板）
    public static final String ROLE_READ = "role:read";         // 角色定义（后台独立面板）
    public static final String PERM_READ = "perm:read";         // 菜单权限（后台独立面板）
    public static final String ADMIN_DOCS = "admin:docs";       // 后台文档管理面板
    public static final String LOGINLOG_READ = "loginlog:read"; // 登录日志（后台独立面板）

    /** 全部权限码，按展示顺序排列，供权限矩阵与单测使用 */
    public static final java.util.List<String> ALL = java.util.List.of(
            ADMIN_ACCESS, CHAT_USE, DOC_READ, DOC_WRITE, DOC_DELETE,
            SEARCH_USE, TREE_USE, USER_READ, USER_WRITE, ORG_READ, ROLE_READ, PERM_READ,
            ADMIN_DOCS, MODEL_READ, MODEL_WRITE, LOGINLOG_READ, AUDIT_READ, USAGE_READ);

    private Permission() { }
}
