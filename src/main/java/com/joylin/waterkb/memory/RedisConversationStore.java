package com.joylin.waterkb.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.joylin.waterkb.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Redis 的对话历史存储（按会话 ID 隔离）。
 *
 * <p>设计决策：
 * <ul>
 *   <li>用 Redis List 存 JSON 消息（role/content/ts），而不是 Spring AI 内置
 *       ChatMemory 接口——自实现可以在存取两端都做裁剪和 TTL 控制，
 *       数据结构一目了然，也方便以后换成多实例共享的独立存储；</li>
 *   <li>每次 append 都 refresh TTL：活跃会话不过期，闲置 24 小时自动清理；</li>
 *   <li>写入用 LPUSH+LTRIM 语义（这里 RPUSH+trim 尾部），保证单会话内存占用有上限，
 *       防止长会话把超长历史全量塞进 Prompt 撑爆 token 预算。</li>
 * </ul></p>
 */
@Service
public class RedisConversationStore {

    private static final Logger log = LoggerFactory.getLogger(RedisConversationStore.class);

    private static final String KEY_PREFIX = "water:rag:history:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RagProperties props;

    public RedisConversationStore(StringRedisTemplate redis, ObjectMapper objectMapper, RagProperties props) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    /**
     * 追加一条对话消息（用户或助手），并刷新过期时间。
     */
    public void append(String sessionId, String role, String content) {
        try {
            Map<String, String> msg = new LinkedHashMap<>();
            msg.put("role", role);
            msg.put("content", content);
            msg.put("ts", LocalDateTime.now().toString());

            String key = key(sessionId);
            redis.opsForList().rightPush(key, objectMapper.writeValueAsString(msg));
            // 只保留最近 N 条，防止长会话无限制增长
            redis.opsForList().trim(key, -props.getHistoryMaxEntries(), -1);
            // ttlHours <= 0 表示永久保存（不设置过期）；> 0 则每次对话刷新过期时间
            if (props.getHistoryTtlHours() > 0) {
                redis.expire(key, Duration.ofHours(props.getHistoryTtlHours()));
            }
        } catch (Exception e) {
            // 历史写入失败不影响主问答流程，降级为"无上下文继续"
            log.warn("对话历史写入 Redis 失败（不影响本次回答）", e);
        }
    }

    /**
     * 取最近 maxTurns 轮对话，拼成文本供 Prompt 使用。
     */
    public String recentAsText(String sessionId, int maxTurns) {
        List<String> list = redis.opsForList().range(key(sessionId), 0, -1);
        if (list == null || list.isEmpty()) {
            return "（无）";
        }
        // 一轮 = 用户 + 助手 两条，取尾部
        int from = Math.max(0, list.size() - maxTurns * 2);
        StringBuilder sb = new StringBuilder();
        for (String json : list.subList(from, list.size())) {
            try {
                JsonNode node = objectMapper.readTree(json);
                String role = "user".equals(node.path("role").asText()) ? "用户" : "助手";
                sb.append(role).append("：").append(node.path("content").asText()).append('\n');
            } catch (Exception e) {
                log.warn("历史消息解析失败，跳过该条", e);
            }
        }
        return sb.length() > 0 ? sb.toString() : "（无）";
    }

    /**
     * 读取完整历史（供前端回显会话）。
     */
    public List<Map<String, String>> getHistory(String sessionId) {
        List<String> list = redis.opsForList().range(key(sessionId), 0, -1);
        List<Map<String, String>> result = new ArrayList<>();
        if (list == null) {
            return result;
        }
        for (String json : list) {
            try {
                JsonNode node = objectMapper.readTree(json);
                Map<String, String> msg = new LinkedHashMap<>();
                msg.put("role", node.path("role").asText());
                msg.put("content", node.path("content").asText());
                msg.put("ts", node.path("ts").asText());
                result.add(msg);
            } catch (Exception e) {
                log.warn("历史消息解析失败，跳过该条", e);
            }
        }
        return result;
    }

    /**
     * 清空指定会话的历史。
     */
    public void clear(String sessionId) {
        redis.delete(key(sessionId));
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
