package com.joylin.waterkb.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限节点目录：菜单权限页的数据源，支持查看、编辑、新增、删除。
 *
 * <p>设计要点：
 * <ul>
 *   <li>节点 = {权限标识 code, 名称, 类型(功能菜单/操作按钮), 所属模块, 说明}；</li>
 *   <li><b>内置节点</b>（Permission.ALL 的 18 个码 + 5 个分组目录）绑定接口级校验，首次启动写入
 *       perm_node 表，可以改显示信息但<b>不可删除、不可改标识</b>——code 是
 *       &#64;RequirePermission 的绑定契约；</li>
 *   <li><b>自定义节点</b>可增删改，配合角色权限编辑分配给角色，前端按 code 控制入口显隐；</li>
 *   <li>RolePermissions 保存角色权限时按本目录校验合法性。</li>
 * </ul></p>
 */
@Service
public class PermissionCatalog {

    /**
     * 内置节点按真实导航排列，权限树即页面地图：
     * 工作台 = 问答侧栏；管理后台 = 后台侧栏（权限配置目录 / 业务管理 / 日志审计）。
     * 列依次为 code, 名称, 类型, 模块(分组), 说明, 上级code, 路由, 排序。
     * 目录节点（*:root）不绑定接口权限码，仅作树形分组，role 不可勾选。
     */
    private static final List<String[]> BUILT_IN = List.of(
            // ===== 工作台：问答侧栏 =====
            new String[]{"workbench:root", "工作台", "directory", "工作台", "问答侧栏：问答、文档与检索", "", "", "10"},
            new String[]{"chat:use", "智能问答", "menu", "工作台", "进入智能问答页", "workbench:root", "/index", "11"},
            new String[]{"doc:read", "知识库文档", "menu", "工作台", "查看知识库文档列表", "workbench:root", "/manage", "12"},
            new String[]{"doc:write", "上传文档", "btn", "工作台", "上传与批量上传文档入库", "doc:read", "", "121"},
            new String[]{"doc:delete", "删除文档", "btn", "工作台", "删除文档及其全部切片", "doc:read", "", "122"},
            new String[]{"search:use", "检索对比", "menu", "工作台", "对比不同检索方式的结果", "workbench:root", "/search", "13"},
            new String[]{"tree:use", "会话结构", "menu", "工作台", "查看与切换问答分支", "workbench:root", "/tree", "14"},
            // ===== 管理后台：后台侧栏三组，与真实导航一一对应 =====
            new String[]{"system:root", "管理后台", "directory", "系统管理", "后台侧栏：权限配置、业务管理与日志审计", "", "/admin", "20"},
            new String[]{"admin:access", "进入管理后台", "menu", "系统管理", "进入管理后台入口", "system:root", "/admin", "201"},
            new String[]{"console.config:root", "权限配置目录", "directory", "系统管理", "后台侧栏第一组", "system:root", "", "21"},
            new String[]{"user:read", "用户管理", "menu", "系统管理", "查看账号列表", "console.config:root", "/admin/users", "211"},
            new String[]{"user:write", "用户维护", "btn", "系统管理", "账号增删改与密码重置", "user:read", "", "2111"},
            new String[]{"org:read", "组织架构", "menu", "系统管理", "查看与维护组织架构", "console.config:root", "/admin/org", "212"},
            new String[]{"role:read", "角色定义", "menu", "系统管理", "查看与维护角色定义", "console.config:root", "/admin/roles", "213"},
            new String[]{"perm:read", "菜单权限", "menu", "系统管理", "查看与维护菜单权限树", "console.config:root", "/admin/perms", "214"},
            new String[]{"console.biz:root", "业务管理", "directory", "系统管理", "后台侧栏第二组", "system:root", "", "22"},
            new String[]{"admin:docs", "文档管理", "menu", "系统管理", "后台文档管理面板（接口仍按 doc:read 校验）", "console.biz:root", "/admin/docs", "221"},
            new String[]{"model:read", "模型设置", "menu", "系统管理", "查看模型配置", "console.biz:root", "/admin/models", "222"},
            new String[]{"model:write", "修改模型", "btn", "系统管理", "增删改与切换模型", "model:read", "", "2221"},
            new String[]{"usage:read", "用量统计", "menu", "系统管理", "查看模型调用用量", "console.biz:root", "/admin/usage", "223"},
            new String[]{"console.log:root", "日志审计", "directory", "系统管理", "后台侧栏第三组", "system:root", "", "23"},
            new String[]{"loginlog:read", "登录日志", "menu", "系统管理", "查看登录日志", "console.log:root", "/admin/loginlogs", "231"},
            new String[]{"audit:read", "操作日志", "menu", "系统管理", "查看操作日志与治理页审计", "console.log:root", "/governance", "232"});

    /** 内置节点改名（旧名 → 新名）：只改仍用旧名的行，用户自定义的名称不覆盖 */
    private static final Map<String, String[]> RENAMES = Map.of(
            "system:root", new String[]{"系统管理", "管理后台"},
            "admin:access", new String[]{"管理后台", "进入管理后台"},
            "tree:use", new String[]{"问答分支", "会话结构"},
            "user:read", new String[]{"用户查看", "用户管理"},
            "audit:read", new String[]{"审计日志", "操作日志"});

    private final JdbcTemplate jdbc;

    public PermissionCatalog(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS perm_node ("
                + "code VARCHAR(64) PRIMARY KEY, name VARCHAR(64) NOT NULL, "
                + "node_type VARCHAR(16) NOT NULL DEFAULT 'menu', module VARCHAR(32) NOT NULL, "
                + "description VARCHAR(256), builtin BOOLEAN NOT NULL DEFAULT FALSE, "
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        // 若依风格三级树字段：平滑升级，旧库也能直接启动
        jdbc.execute("ALTER TABLE perm_node ADD COLUMN IF NOT EXISTS parent_code VARCHAR(64) DEFAULT ''");
        jdbc.execute("ALTER TABLE perm_node ADD COLUMN IF NOT EXISTS route_path VARCHAR(128) DEFAULT ''");
        jdbc.execute("ALTER TABLE perm_node ADD COLUMN IF NOT EXISTS order_num INT DEFAULT 0");
        // 内置节点首次初始化写入，之后以库为准（用户改过的显示信息不会被启动覆盖）
        for (String[] n : BUILT_IN) {
            jdbc.update("INSERT INTO perm_node(code,name,node_type,module,description,builtin,parent_code,route_path,order_num) "
                            + "VALUES(?,?,?,?,?,TRUE,?,?,?) ON CONFLICT (code) DO NOTHING",
                    n[0], n[1], n[2], n[3], n[4], n[5], n[6], Integer.parseInt(n[7]));
        }
        // 存量内置节点按真实导航重排（结构字段以代码为准，避免老库层级对不上页面）；
        // 名称只在仍是旧名时改，管理员改过的显示名保留。
        for (String[] n : BUILT_IN) {
            jdbc.update("UPDATE perm_node SET parent_code=?, route_path=?, order_num=?, node_type=?, module=? "
                            + "WHERE code=? AND builtin=TRUE",
                    n[5], n[6], Integer.parseInt(n[7]), n[2], n[3], n[0]);
        }
        RENAMES.forEach((code, pair) -> jdbc.update(
                "UPDATE perm_node SET name=? WHERE code=? AND builtin=TRUE AND name=?",
                pair[1], code, pair[0]));
    }

    /** 全部权限节点（内置 + 自定义），按模块与内置顺序排列 */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        jdbc.query("SELECT code,name,node_type,module,description,builtin,parent_code,route_path,order_num "
                + "FROM perm_node ORDER BY builtin DESC, order_num, code", rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", rs.getString("code"));
            m.put("name", rs.getString("name"));
            m.put("nodeType", rs.getString("node_type"));
            m.put("module", rs.getString("module"));
            m.put("description", rs.getString("description"));
            m.put("builtin", rs.getBoolean("builtin"));
            m.put("parentCode", rs.getString("parent_code") == null ? "" : rs.getString("parent_code"));
            m.put("routePath", rs.getString("route_path") == null ? "" : rs.getString("route_path"));
            m.put("orderNum", rs.getInt("order_num"));
            out.add(m);
        });
        return out;
    }

    /** 权限标识是否存在于目录（角色保存时的合法性校验用） */
    public boolean exists(String code) {
        Boolean hit = jdbc.queryForObject(
                "SELECT COUNT(*) > 0 FROM perm_node WHERE code=?", Boolean.class, code);
        return Boolean.TRUE.equals(hit);
    }

    /** 新增自定义节点：标识格式 xxx:yyy 且不与现有节点冲突（兼容旧 5 参调用） */
    public void create(String code, String name, String type, String module, String desc) {
        create(code, name, type, module, desc, "", "", 0);
    }

    /** 若依风格新增：带上级节点、路由路径、显示顺序 */
    public void create(String code, String name, String type, String module, String desc,
                       String parentCode, String routePath, int orderNum) {
        validateCode(code);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("权限名称不能为空");
        String t = normalizeType(type);
        try {
            jdbc.update("INSERT INTO perm_node(code,name,node_type,module,description,builtin,parent_code,route_path,order_num) "
                            + "VALUES(?,?,?,?,?,FALSE,?,?,?)",
                    code.trim(), name.trim(), t,
                    module == null || module.isBlank() ? "自定义" : module.trim(),
                    desc == null ? "" : desc.trim(),
                    parentCode == null ? "" : parentCode.trim(),
                    routePath == null ? "" : routePath.trim(), orderNum);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalArgumentException("权限标识已存在：" + code);
        }
    }

    /** 编辑节点：内置节点只能改显示信息（code 不可变、不可删）；自定义节点全可改（兼容旧 5 参调用） */
    public void update(String code, String name, String type, String module, String desc) {
        update(code, name, type, module, desc, "", "", 0);
    }

    /** 若依风格编辑：可改上级节点、路由路径、显示顺序 */
    public void update(String code, String name, String type, String module, String desc,
                       String parentCode, String routePath, int orderNum) {
        if (!exists(code)) throw new IllegalArgumentException("权限节点不存在：" + code);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("权限名称不能为空");
        String t = normalizeType(type);
        jdbc.update("UPDATE perm_node SET name=?, node_type=?, module=?, description=?, parent_code=?, route_path=?, order_num=? WHERE code=?",
                name.trim(), t,
                module == null || module.isBlank() ? "自定义" : module.trim(),
                desc == null ? "" : desc.trim(),
                parentCode == null ? "" : parentCode.trim(),
                routePath == null ? "" : routePath.trim(), orderNum, code);
    }

    private String normalizeType(String type) {
        return "btn".equals(type) ? "btn" : ("directory".equals(type) ? "directory" : "menu");
    }

    /** 删除节点：内置节点是接口校验契约，不允许删除 */
    public void delete(String code) {
        if (!exists(code)) throw new IllegalArgumentException("权限节点不存在：" + code);
        Boolean builtin = jdbc.queryForObject("SELECT builtin FROM perm_node WHERE code=?",
                Boolean.class, code);
        if (Boolean.TRUE.equals(builtin)) {
            throw new IllegalArgumentException("内置权限节点不可删除（它是接口校验的绑定契约）");
        }
        jdbc.update("DELETE FROM perm_node WHERE code=?", code);
        // 目录里删掉的权限码，各角色配置里也要同步清理
        jdbc.update("DELETE FROM role_permissions WHERE permission=?", code);
    }

    /** code 只允许小写字母/数字/下划线/连字符，且必须带一个冒号分层（如 report:view） */
    private void validateCode(String code) {
        if (code == null || !code.matches("^[a-z][a-z0-9_-]*:[a-z][a-z0-9_-]*$")) {
            throw new IllegalArgumentException("权限标识格式：小写字母开头，形如 report:view");
        }
    }
}
