package com.joylin.waterkb.memory;

import com.joylin.waterkb.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话清单持久化（PostgreSQL chat_session 表）。
 *
 * <p>为什么单独建表而不塞进 Redis：会话清单是"索引"，要能跨浏览器、跨设备、
 * 清缓存后依然在，并且要能按时间排序查询；消息树是"内容"，读写频繁、结构是树，
 * 放 Redis 更合适。清单落库 + 内容留 Redis，是这一层的职责划分。</p>
 *
 * <p>主键用 (账号, sessionId) 二元组：sessionId 是前端生成的
 * {@code session-<毫秒>}，不同账号极可能撞出同一个值，只用 sessionId 做主键
 * 会让后登录的人直接顶掉前一个人的会话。<b>刻意不把租户放进主键与查询条件</b>——
 * 会话的主人是"人"不是"组织"：账号换了组织（组织合并/迁移）之后，历史会话
 * 必须原样跟着人走。旧版按 (租户,账号,sessionId) 存取，账号一换组织，
 * 新租户下清单查出来是空的，前端把浏览器缓存当"存量"整体回灌（列表莫名变多），
 * 而旧租户下的会话全部不可见（列表莫名消失）——这就是那个要根治的 bug。</p>
 *
 * <p>数据库不可用时全部方法降级为"无操作"而不是抛异常：会话列表属于辅助功能，
 * 不能因为它挂了就把整个问答页拖下水（前端仍有 localStorage 兜底）。</p>
 */
@Service
public class ChatSessionStore {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionStore.class);

    private final JdbcTemplate jdbc;
    /** 建表只尝试一次，失败后不再反复刷日志 */
    private volatile boolean schemaReady = false;
    /** 旧三元组主键的一次性迁移是否已完成 */
    private volatile boolean migrationDone = false;

    /** 生产构造：注入数据源。两个构造器必须显式标 @Autowired，否则 Spring 会挑无参那个，jdbc 恒为 null */
    @Autowired
    public ChatSessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 兼容单测：无数据库 */
    public ChatSessionStore() {
        this(null);
    }

    private void ensureSchema() {
        if (schemaReady || jdbc == null) return;
        synchronized (this) {
            if (schemaReady) return;
            try {
                jdbc.execute("CREATE TABLE IF NOT EXISTS chat_session ("
                        + "session_id VARCHAR(80) NOT NULL, "
                        + "user_id VARCHAR(64) NOT NULL, "
                        + "tenant_id VARCHAR(64), "
                        + "title VARCHAR(200), "
                        + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                        + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                        + "PRIMARY KEY (user_id, session_id))");
                migrateToUserKeyed();
                jdbc.execute("DROP INDEX IF EXISTS idx_chat_session_owner");
                jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chat_session_owner_v2 "
                        + "ON chat_session(user_id, updated_at DESC)");
                schemaReady = true;
            } catch (Exception e) {
                log.warn("会话清单表不可用，会话列表仅保存在浏览器本地：{}", e.getMessage());
            }
        }
    }

    /**
     * 旧三元组主键 (tenant_id,user_id,session_id) 的一次性迁移。
     *
     * <p>先去重（同一账号同一 sessionId 在多个租户下各有一行时保留最新），
     * 再把主键换成 (user_id, session_id)。失败不阻塞启动，下次再试。</p>
     */
    private void migrateToUserKeyed() {
        if (migrationDone) return;
        try {
            Boolean oldPk = jdbc.queryForObject(
                    "SELECT COUNT(*) > 0 FROM pg_constraint c JOIN pg_class t ON c.conrelid = t.oid "
                            + "WHERE t.relname = 'chat_session' AND c.contype = 'p' "
                            + "AND pg_get_constraintdef(c.oid) LIKE '%tenant_id%'", Boolean.class);
            if (!Boolean.TRUE.equals(oldPk)) { migrationDone = true; return; }
            // 同一账号同一 sessionId 多租户各行：保留 updated_at 最新的一行
            jdbc.update("DELETE FROM chat_session a USING chat_session b "
                    + "WHERE a.user_id = b.user_id AND a.session_id = b.session_id "
                    + "AND (a.updated_at < b.updated_at "
                    + "OR (a.updated_at = b.updated_at AND a.tenant_id < b.tenant_id))");
            jdbc.execute("ALTER TABLE chat_session DROP CONSTRAINT chat_session_pkey");
            jdbc.execute("ALTER TABLE chat_session ADD PRIMARY KEY (user_id, session_id)");
            migrationDone = true;
            log.info("chat_session 主键已迁移为 (user_id, session_id)：会话跟随账号，不再随组织漂移");
        } catch (Exception e) {
            log.warn("会话清单主键迁移未完成（下次启动重试）：{}", e.getMessage());
        }
    }

    private static String tenant() {
        UserContext.CurrentUser u = UserContext.get();
        String t = u == null ? null : u.tenantId();
        return t == null || t.isBlank() ? "default" : t.trim();
    }

    private static String user() {
        UserContext.CurrentUser u = UserContext.get();
        String id = u == null ? null : u.userId();
        return id == null || id.isBlank() ? "anonymous" : id.trim();
    }

    /**
     * 当前账号的会话清单，按最近更新倒序。
     *
     * <p>只返回有标题的行：标题是"首次提问"时才补写的，没有标题说明这个会话
     * 建了但一句没问过。历史会话列表是"记录"，空会话不该占位——否则新建会话、
     * 切走、再进来，历史里躺着一串「会话 179542」。前端同样按此过滤（双保险：
     * 换设备、换浏览器时以服务端清单为准）。<b>只按账号查、不按租户查</b>：
     * 账号换组织后历史会话原样可见。</p>
     */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (jdbc == null) return out;
        ensureSchema();
        if (!schemaReady) return out;
        try {
            // 时间戳一定要走 JDBC 的 getTimestamp().getTime()，不要在 SQL 里 EXTRACT(EPOCH FROM ...)。
            // created_at/updated_at 是「无时区 TIMESTAMP」，写入时按 JVM 时区落的是本地墙上时间；
            // 而 EXTRACT(EPOCH FROM timestamp) 会按 PG session 时区（容器里是 Etc/UTC）去解释它，
            // 北京时间就被当成 UTC，凭空多出 8 小时——列表里刚聊过的会话显示成明天凌晨，
            // 排序自然全乱（"最新"被算成未来时刻排最前，真正刚聊的沉到最后）。
            // getTimestamp 由驱动按同一个 JVM 时区解释，与写入自洽。
            jdbc.query("SELECT session_id, title, created_at, updated_at "
                            + "FROM chat_session WHERE user_id=? "
                            + "AND title IS NOT NULL AND title <> '' "
                            + "ORDER BY updated_at DESC",
                    rs -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", rs.getString("session_id"));
                        m.put("title", rs.getString("title") == null ? "" : rs.getString("title"));
                        java.sql.Timestamp created = rs.getTimestamp("created_at");
                        java.sql.Timestamp updated = rs.getTimestamp("updated_at");
                        m.put("createdAt", created == null ? 0L : created.getTime());
                        m.put("updatedAt", updated == null ? 0L : updated.getTime());
                        out.add(m);
                    }, user());
        } catch (Exception e) {
            log.warn("读取会话清单失败：{}", e.getMessage());
        }
        return out;
    }

    /**
     * 某个组织名下的会话条数（删除组织前盘家底用）。
     */
    public int countByTenant(String tenant) {
        if (jdbc == null || tenant == null || tenant.isBlank()) return 0;
        ensureSchema();
        if (!schemaReady) return 0;
        try {
            Long n = jdbc.queryForObject("SELECT COUNT(*) FROM chat_session WHERE tenant_id=?", Long.class, tenant);
            return n == null ? 0 : n.intValue();
        } catch (Exception e) { return 0; }
    }

    /**
     * 组织合并时把会话一并迁到新组织。
     *
     * <p>和文档同一个道理：会话 key 里带租户，账号迁走了会话还挂在旧租户上，
     * 旧组织一删，这些历史问答就跟着失联。</p>
     */
    public int migrateTenant(String from, String to) {
        if (jdbc == null || from == null || from.isBlank() || to == null || to.isBlank() || from.equals(to)) return 0;
        ensureSchema();
        if (!schemaReady) return 0;
        try {
            return jdbc.update("UPDATE chat_session SET tenant_id=? WHERE tenant_id=?", to, from);
        } catch (Exception e) {
            log.warn("会话迁移失败 {} → {}：{}", from, to, e.getMessage());
            return 0;
        }
    }

    /**
     * 新建或刷新会话。
     *
     * @param title 传空表示"不改标题"：首问才产生标题，后续每轮只更新时间戳
     * @return 是否写入成功（数据库不可用时 false，前端靠本地缓存继续工作）
     */
    public boolean upsert(String sessionId, String title) {
        if (jdbc == null || sessionId == null || sessionId.isBlank()) return false;
        ensureSchema();
        if (!schemaReady) return false;
        String t = title == null ? "" : title.trim();
        if (t.length() > 200) t = t.substring(0, 200);
        try {
            // 标题只在原本为空时补写：首问之后用户可能已手动改过标题，
            // 不能每轮都被问题文本覆盖回去。tenant_id 只是记录值，不再参与主键与查询
            jdbc.update("INSERT INTO chat_session(session_id,user_id,tenant_id,title,created_at,updated_at) "
                            + "VALUES(?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) "
                            + "ON CONFLICT(user_id, session_id) DO UPDATE SET "
                            + "title=CASE WHEN chat_session.title IS NULL OR chat_session.title='' "
                            + "THEN EXCLUDED.title ELSE chat_session.title END, "
                            + "updated_at=CURRENT_TIMESTAMP",
                    sessionId, user(), tenant(), t);
            return true;
        } catch (Exception e) {
            log.warn("写入会话清单失败：{}", e.getMessage());
            return false;
        }
    }

    /** 改名（用户在会话列表里重命名时用） */
    public boolean rename(String sessionId, String title) {
        if (jdbc == null || sessionId == null || sessionId.isBlank()) return false;
        ensureSchema();
        if (!schemaReady) return false;
        String t = title == null ? "" : title.trim();
        if (t.length() > 200) t = t.substring(0, 200);
        try {
            return jdbc.update("UPDATE chat_session SET title=?, updated_at=CURRENT_TIMESTAMP "
                    + "WHERE user_id=? AND session_id=?", t, user(), sessionId) > 0;
        } catch (Exception e) {
            log.warn("重命名会话失败：{}", e.getMessage());
            return false;
        }
    }

    /** 删除会话清单行 */
    public boolean delete(String sessionId) {
        if (jdbc == null || sessionId == null || sessionId.isBlank()) return false;
        ensureSchema();
        if (!schemaReady) return false;
        try {
            return jdbc.update("DELETE FROM chat_session WHERE user_id=? AND session_id=?",
                    user(), sessionId) > 0;
        } catch (Exception e) {
            log.warn("删除会话清单失败：{}", e.getMessage());
            return false;
        }
    }
}
