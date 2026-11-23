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
 *   <li><b>内置节点</b>（Permission.ALL 的 13 个码）绑定接口级校验，首次启动写入
 *       perm_node 表，可以改显示信息但<b>不可删除、不可改标识</b>——code 是
 *       &#64;RequirePermission 的绑定契约；</li>
 *   <li><b>自定义节点</b>可增删改，配合角色权限编辑分配给角色，前端按 code 控制入口显隐；</li>
 *   <li>RolePermissions 保存角色权限时按本目录校验合法性。</li>
 * </ul></p>
 */
@Service
public class PermissionCatalog {

    /** 内置节点：code 与 Permission.ALL 一一对应，name/type/module/desc 是菜单页的展示信息 */
    private static final List<String[]> BUILT_IN = List.of(
            new String[]{"chat:use", "智能问答", "menu", "工作台", "进入问答页并使用知识库问答"},
            new String[]{"doc:read", "知识库文档", "menu", "工作台", "查看知识库文档列表"},
            new String[]{"doc:write", "上传文档", "btn", "工作台", "上传文档入库并解析切片"},
            new String[]{"doc:delete", "删除文档", "btn", "工作台", "删除文档及其全部切片"},
            new String[]{"search:use", "检索对比", "menu", "工作台", "对比不同检索方式的结果"},
            new String[]{"tree:use", "问答分支", "menu", "工作台", "查看与切换问答分支"},
            new String[]{"admin:access", "管理后台", "menu", "系统管理", "进入管理后台"},
            new String[]{"model:read", "模型设置", "menu", "系统管理", "查看模型配置"},
            new String[]{"model:write", "修改模型", "btn", "系统管理", "增删改与切换模型"},
            new String[]{"audit:read", "审计日志", "menu", "系统管理", "查看操作与登录日志"},
            new String[]{"usage:read", "用量统计", "menu", "系统管理", "查看模型调用用量"},
            new String[]{"user:read", "用户查看", "menu", "系统管理", "查看账号列表"},
            new String[]{"user:write", "用户维护", "btn", "系统管理", "账号增删改与密码重置"});

    private final JdbcTemplate jdbc;

    public PermissionCatalog(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS perm_node ("
                + "code VARCHAR(64) PRIMARY KEY, name VARCHAR(64) NOT NULL, "
                + "node_type VARCHAR(16) NOT NULL DEFAULT 'menu', module VARCHAR(32) NOT NULL, "
                + "description VARCHAR(256), builtin BOOLEAN NOT NULL DEFAULT FALSE, "
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        // 内置节点首次初始化写入，之后以库为准（用户改过的显示信息不会被启动覆盖）
        for (String[] n : BUILT_IN) {
            jdbc.update("INSERT INTO perm_node(code,name,node_type,module,description,builtin) "
                            + "VALUES(?,?,?,?,?,TRUE) ON CONFLICT (code) DO NOTHING",
                    n[0], n[1], n[2], n[3], n[4]);
        }
    }

    /** 全部权限节点（内置 + 自定义），按模块与内置顺序排列 */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        jdbc.query("SELECT code,name,node_type,module,description,builtin FROM perm_node "
                + "ORDER BY builtin DESC, module, code", rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", rs.getString("code"));
            m.put("name", rs.getString("name"));
            m.put("nodeType", rs.getString("node_type"));
            m.put("module", rs.getString("module"));
            m.put("description", rs.getString("description"));
            m.put("builtin", rs.getBoolean("builtin"));
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

    /** 新增自定义节点：标识格式 xxx:yyy 且不与现有节点冲突 */
    public void create(String code, String name, String type, String module, String desc) {
        validateCode(code);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("权限名称不能为空");
        String t = "btn".equals(type) ? "btn" : "menu";
        try {
            jdbc.update("INSERT INTO perm_node(code,name,node_type,module,description,builtin) "
                            + "VALUES(?,?,?,?,?,FALSE)",
                    code.trim(), name.trim(), t,
                    module == null || module.isBlank() ? "自定义" : module.trim(),
                    desc == null ? "" : desc.trim());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalArgumentException("权限标识已存在：" + code);
        }
    }

    /** 编辑节点：内置节点只能改显示信息（code 不可变、不可删）；自定义节点全可改 */
    public void update(String code, String name, String type, String module, String desc) {
        if (!exists(code)) throw new IllegalArgumentException("权限节点不存在：" + code);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("权限名称不能为空");
        String t = "btn".equals(type) ? "btn" : "menu";
        jdbc.update("UPDATE perm_node SET name=?, node_type=?, module=?, description=? WHERE code=?",
                name.trim(), t,
                module == null || module.isBlank() ? "自定义" : module.trim(),
                desc == null ? "" : desc.trim(), code);
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
