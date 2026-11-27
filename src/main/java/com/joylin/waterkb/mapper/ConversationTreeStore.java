package com.joylin.waterkb.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.joylin.waterkb.config.RagProperties;
import com.joylin.waterkb.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.joylin.waterkb.model.TreeNode;

/**
 * 会话消息树存储（pi-mono 风格 fork/rewind 的地基）。
 *
 *
 *
 */
@Service
public class ConversationTreeStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationTreeStore.class);
    public static final String ROOT = "root";

    private static final String TREE = "water:rag:tree:";
    private static final String HEAD = "water:rag:head:";
    private static final String BRANCH = "water:rag:branches:";

    /**
     * Redis key = 前缀 + 租户 + 账号 + sessionId。
     *
     */
    private String key(String prefix, String sessionId) {
        UserContext.CurrentUser u = UserContext.get();
        return keyFor(prefix, u.tenantId(), u.userId(), sessionId);
    }

    private String keyFor(String prefix, String tenant, String user, String sessionId) {
        String t = tenant == null || tenant.isBlank() ? "default" : tenant.trim();
        String u = user == null || user.isBlank() ? "anonymous" : user.trim();
        return prefix + t + ":" + u + ":" + sessionId;
    }

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final RagProperties props;

    public ConversationTreeStore(StringRedisTemplate redis, ObjectMapper mapper, RagProperties props) {
        this.redis = redis;
        this.mapper = mapper;
        this.props = props;
    }

    /**
     * 追加节点，返回新节点 ID。
     *
     */
    public String append(String sessionId, String parentId, String role, String content,
                         List<Map<String, Object>> sources, String branch) {
        UserContext.CurrentUser u = UserContext.get();
        return append(sessionId, u.tenantId(), u.userId(), parentId, role, content, sources, branch);
    }

    /**
     * 显式传租户+账号的 append——给 Reactor 异步回调用。
     * ThreadLocal 在 SSE 流完成时已被 Filter 清除，异步线程读不到原身份，
     * 必须由同步阶段捕获身份快照传进来，否则消息会串到其他账号/租户。
     */
    public String append(String sessionId, String tenant, String user, String parentId, String role,
                         String content, List<Map<String, Object>> sources, String branch) {
        return append(sessionId, tenant, user, parentId, role, content, sources, branch, null);
    }

    /**
     * 带「本轮消耗」的追加（assistant 节点用）。
     *
     */
    public String append(String sessionId, String tenant, String user, String parentId, String role,
                         String content, List<Map<String, Object>> sources, String branch,
                         Map<String, Object> usage) {
        String id = UUID.randomUUID().toString();
        String parent = parentId == null || parentId.isBlank() ? ROOT : parentId;
        // 防悬空节点：前端传来的 parentId 必须真实存在，否则拒收并回退到当前 head。
        // 不校验的话树会悄悄长出孤儿分支——节点还在 Redis 里但画不出来、检索不到。
        if (!ROOT.equals(parent) && get(sessionId, tenant, user, parent).isEmpty()) {
            log.warn("parentId={} 不存在，回退到当前 head", parent);
            String h = head(sessionId, tenant, user);
            parent = h == null ? ROOT : h;
        }
        TreeNode node = new TreeNode(id, parent, role, content, LocalDateTime.now().toString(),
                sources == null ? List.of() : sources, branch, usage);
        try {
            redis.opsForHash().put(keyFor(TREE, tenant, user, sessionId), id, mapper.writeValueAsString(node));
            redis.opsForValue().set(keyFor(HEAD, tenant, user, sessionId), id);
            if (branch != null && !branch.isBlank()) {
                redis.opsForHash().put(keyFor(BRANCH, tenant, user, sessionId), branch, id);
            }
            refreshTtl(sessionId, tenant, user);
        } catch (Exception e) {
            log.warn("消息树写入失败（不影响本次回答）", e);
        }
        return id;
    }

    /**

     * 当前 head（null 表示会话还没有消息）

     */
    public String head(String sessionId) {
        UserContext.CurrentUser u = UserContext.get();
        return head(sessionId, u.tenantId(), u.userId());
    }

    public String head(String sessionId, String tenant, String user) {
        try {
            return redis.opsForValue().get(keyFor(HEAD, tenant, user, sessionId));
        } catch (Exception e) {
            log.warn("读取会话 head 失败，按无历史处理：{}", e.getMessage());
            return null;
        }
    }

    /**

     * rewind：把 head 移到指定节点；节点必须存在，否则拒绝（防止指针悬空）。

     */
    public boolean rewind(String sessionId, String nodeId) {
        // 回退到会话开头：root 是虚拟节点、不存在于 Redis，直接删掉 head 指针即可。
        // 删掉之后 append(null) 会挂到 root 下，等同于"从第一问重新问一次"。
        if (ROOT.equals(nodeId)) {
            redis.delete(key(HEAD, sessionId));
            refreshTtl(sessionId);
            return true;
        }
        if (nodeId == null || !get(sessionId, nodeId).isPresent()) return false;
        redis.opsForValue().set(key(HEAD, sessionId), nodeId);
        refreshTtl(sessionId);
        return true;
    }

    /**

     * 取单个节点

     */
    public java.util.Optional<TreeNode> get(String sessionId, String nodeId) {
        UserContext.CurrentUser u = UserContext.get();
        return get(sessionId, u.tenantId(), u.userId(), nodeId);
    }

    public java.util.Optional<TreeNode> get(String sessionId, String tenant, String user, String nodeId) {
        try {
            Object raw = redis.opsForHash().get(keyFor(TREE, tenant, user, sessionId), nodeId);
            if (raw == null) return java.util.Optional.empty();
            return java.util.Optional.of(mapper.readValue(raw.toString(), TreeNode.class));
        } catch (Exception e) {
            log.warn("读取节点失败：{}", nodeId, e);
            return java.util.Optional.empty();
        }
    }

    /**
     * 从某节点沿 parentId 回溯到根，再正序返回。
     * 这就是"当前分支的对话链"——Prompt 历史只取这条链，兄弟分支不会串进来。
     */
    public List<TreeNode> pathTo(String sessionId, String fromNodeId) {
        String cursor = fromNodeId == null ? head(sessionId) : fromNodeId;
        Deque<TreeNode> stack = new ArrayDeque<>();
        Set<Object> seen = new java.util.HashSet<>();
        while (cursor != null && !ROOT.equals(cursor) && seen.add(cursor)) {
            java.util.Optional<TreeNode> n = get(sessionId, cursor);
            if (n.isEmpty()) break;
            stack.push(n.get());
            cursor = n.get().parentId();
        }
        return new ArrayList<>(stack);
    }

    /**

     * 当前 head 所在分支的完整消息链

     */
    public List<TreeNode> path(String sessionId) {
        return pathTo(sessionId, null);
    }

    /**

     * 取整棵树的所有节点（供前端画树）

     */
    public List<TreeNode> allNodes(String sessionId) {
        Map<Object, Object> entries = redis.opsForHash().entries(key(TREE, sessionId));
        List<TreeNode> out = new ArrayList<>(entries.size());
        for (Object raw : entries.values()) {
            try {
                out.add(mapper.readValue(raw.toString(), TreeNode.class));
            } catch (Exception e) {
                log.warn("节点反序列化失败，跳过", e);
            }
        }
        out.sort((a, b) -> a.ts().compareTo(b.ts()));
        return out;
    }

    /**

     * 命名分支表：branchName → fork 起点 nodeId

     */
    public Map<String, String> branches(String sessionId) {
        Map<Object, Object> raw = redis.opsForHash().entries(key(BRANCH, sessionId));
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        return out;
    }

    /**

     * 命名分支：把某个节点标记为新分支的起点（真正的分叉在下一次 append 发生）

     */
    public boolean fork(String sessionId, String fromNodeId, String branchName) {
        if (branchName == null || branchName.isBlank()) return false;
        if (!get(sessionId, fromNodeId).isPresent()) return false;
        redis.opsForHash().put(key(BRANCH, sessionId), branchName, fromNodeId);
        refreshTtl(sessionId);
        return true;
    }

    /**
     * 拼 Prompt 历史：只取当前分支最近 maxTurns 轮（一轮=user+assistant 两条）。
     */
    public String recentAsText(String sessionId, int maxTurns) {
        List<TreeNode> path = path(sessionId);
        int from = Math.max(0, path.size() - maxTurns * 2);
        StringBuilder sb = new StringBuilder();
        for (TreeNode n : path.subList(from, path.size())) {
            sb.append("user".equals(n.role()) ? "用户" : "助手").append("：")
                    .append(n.content()).append('\n');
        }
        return sb.length() > 0 ? sb.toString() : "（无）";
    }

    /**
     * 兼容旧接口：当前分支的线性消息列表。
     *
     */
    public List<Map<String, Object>> historyList(String sessionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TreeNode n : path(sessionId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", n.role());
            m.put("content", n.content());
            m.put("ts", n.ts());
            m.put("id", n.id());
            m.put("parentId", n.parentId());
            m.put("sources", n.sources());
            m.put("usage", n.usage());
            out.add(m);
        }
        return out;
    }

    /**

     * 清空整个会话（树、head、分支一起删）

     */
    public void clear(String sessionId) {
        redis.delete(java.util.List.of(key(TREE, sessionId), key(HEAD, sessionId), key(BRANCH, sessionId)));
    }

    private void refreshTtl(String sessionId) {
        UserContext.CurrentUser u = UserContext.get();
        refreshTtl(sessionId, u.tenantId(), u.userId());
    }

    private void refreshTtl(String sessionId, String tenant, String user) {
        if (props.getHistoryTtlHours() > 0) {
            Duration ttl = Duration.ofHours(props.getHistoryTtlHours());
            redis.expire(keyFor(TREE, tenant, user, sessionId), ttl);
            redis.expire(keyFor(HEAD, tenant, user, sessionId), ttl);
            redis.expire(keyFor(BRANCH, tenant, user, sessionId), ttl);
        }
    }
}
