package com.joylin.waterkb.mapper;

import com.joylin.waterkb.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话清单持久化（PostgreSQL chat_session 表）。
 *
 *
 *
 */
@Service
public class ChatSessionStore {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionStore.class);

    private final ChatSessionMapper mapper;
    /**
     * 建表只尝试一次，失败后不再反复刷日志
     */
    /**
     * 旧三元组主键的一次性迁移是否已完成
     */
    private volatile boolean migrationDone = false;

    /**

     * 兼容旧调用（单测）

     */
    /**
     * 生产构造：注入数据源与 Mapper
     */
    @Autowired
    public ChatSessionStore(ChatSessionMapper mapper) { this.mapper = mapper; }

    /**

     * 兼容单测：无数据库

     */
    public ChatSessionStore() {
        this(null);
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
     */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (mapper == null) return out;
        try {
            // 时间戳一定要走 JDBC 的 getTimestamp().getTime()，不要在 SQL 里 EXTRACT(EPOCH FROM ...)。
            // created_at/updated_at 是「无时区 TIMESTAMP」，写入时按 JVM 时区落的是本地墙上时间；
            // 而 EXTRACT(EPOCH FROM timestamp) 会按 PG session 时区（容器里是 Etc/UTC）去解释它，
            // 北京时间就被当成 UTC，凭空多出 8 小时——列表里刚聊过的会话显示成明天凌晨，
            // 排序自然全乱（"最新"被算成未来时刻排最前，真正刚聊的沉到最后）。
            // getTimestamp 由驱动按同一个 JVM 时区解释，与写入自洽。
            for (Map<String, Object> row : mapper.listByUser(user())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", row.get("id"));
                m.put("title", row.get("title") == null ? "" : row.get("title"));
                java.sql.Timestamp created = (java.sql.Timestamp) row.get("createdAt");
                java.sql.Timestamp updated = (java.sql.Timestamp) row.get("updatedAt");
                m.put("createdAt", created == null ? 0L : created.getTime());
                m.put("updatedAt", updated == null ? 0L : updated.getTime());
                out.add(m);
            }
        } catch (Exception e) {
            log.warn("读取会话清单失败：{}", e.getMessage());
        }
        return out;
    }

    /**
     * 某个组织名下的会话条数（删除组织前盘家底用）。
     */
    public int countByTenant(String tenant) {
        if (mapper == null || tenant == null || tenant.isBlank()) return 0;
        try {
            Long n = (long) mapper.countByTenant(tenant);
            return n == null ? 0 : n.intValue();
        } catch (Exception e) { return 0; }
    }

    /**
     * 组织合并时把会话一并迁到新组织。
     *
     */
    public int migrateTenant(String from, String to) {
        if (mapper == null || from == null || from.isBlank() || to == null || to.isBlank() || from.equals(to)) return 0;
        try {
            return mapper.migrateTenant(from, to);
        } catch (Exception e) {
            log.warn("会话迁移失败 {} → {}：{}", from, to, e.getMessage());
            return 0;
        }
    }

    /**
     * 新建或刷新会话。
     *
     * @return 是否写入成功（数据库不可用时 false，前端靠本地缓存继续工作）
     */
    public boolean upsert(String sessionId, String title) {
        if (mapper == null || sessionId == null || sessionId.isBlank()) return false;
        String t = title == null ? "" : title.trim();
        if (t.length() > 200) t = t.substring(0, 200);
        try {
            // 标题只在原本为空时补写：首问之后用户可能已手动改过标题，
            // 不能每轮都被问题文本覆盖回去。tenant_id 只是记录值，不再参与主键与查询
            mapper.upsert(sessionId, user(), tenant(), t);
            return true;
        } catch (Exception e) {
            log.warn("写入会话清单失败：{}", e.getMessage());
            return false;
        }
    }

    /**

     * 改名（用户在会话列表里重命名时用）

     */
    /**
     * 会话是否属于当前账号（显式归属校验用）
     */
    public boolean exists(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || mapper == null) return false;
        try {
            Integer n = mapper.exists(user(), sessionId);
            return n != null && n > 0;
        } catch (Exception e) { return false; }
    }

    public boolean rename(String sessionId, String title) {
        if (mapper == null || sessionId == null || sessionId.isBlank()) return false;
        String t = title == null ? "" : title.trim();
        if (t.length() > 200) t = t.substring(0, 200);
        try {
            return mapper.rename(t, user(), sessionId) > 0;
        } catch (Exception e) {
            log.warn("重命名会话失败：{}", e.getMessage());
            return false;
        }
    }

    /**

     * 删除会话清单行

     */
    public boolean delete(String sessionId) {
        if (mapper == null || sessionId == null || sessionId.isBlank()) return false;
        try {
            return mapper.delete(user(), sessionId) > 0;
        } catch (Exception e) {
            log.warn("删除会话清单失败：{}", e.getMessage());
            return false;
        }
    }
}
