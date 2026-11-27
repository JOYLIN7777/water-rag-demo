package com.joylin.waterkb.model;

import java.util.List;
import java.util.Map;

/**
 * 消息树节点（pi-mono 风格会话模型的最小单元）。
 *
 * <p>与线性历史的区别：每条消息都记 parentId，因此一条消息可以有多个子节点——
 * 这就是 fork（分叉）的结构基础；rewind 只是把 head 指针移回旧节点，
 * 不删除任何节点，所以旧分支永远可找回。</p>
 *
 * @param id       节点 ID（UUID）
 * @param parentId 父节点 ID，根节点用 "root"
 * @param role     user / assistant
 * @param content  消息正文
 * @param ts       ISO 时间戳
 * @param sources  该回答引用的知识片段（仅 assistant 有，回显引用卡片用）
 * @param branch   分支名（可选，仅分叉起点标记，用于树视图标注）
 * @param usage    该轮真实消耗（仅 assistant 有：promptTokens/completionTokens/totalTokens/model/durationMs）。
 *                 跟着节点一起存，切会话、换浏览器重新拉历史时"本次消耗"才不会凭空消失。
 */
public record TreeNode(
        String id,
        String parentId,
        String role,
        String content,
        String ts,
        List<Map<String, Object>> sources,
        String branch,
        Map<String, Object> usage) {

    public TreeNode {
        sources = sources == null ? List.of() : sources;
    }
}
