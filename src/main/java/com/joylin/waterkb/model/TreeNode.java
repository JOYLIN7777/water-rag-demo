package com.joylin.waterkb.model;

import java.util.List;
import java.util.Map;

/**
 * 消息树节点。
 */
public record TreeNode(
        /**
         * 节点标识。
         */
        String id,

        /**
         * 父节点标识。
         */
        String parentId,

        /**
         * 消息角色。
         */
        String role,

        /**
         * 消息正文。
         */
        String content,

        /**
         * 消息时间。
         */
        String ts,

        /**
         * 引用片段。
         */
        List<Map<String, Object>> sources,

        /**
         * 分支名称。
         */
        String branch,

        /**
         * 调用用量。
         */
        Map<String, Object> usage) {

    /**
     * 构造消息树节点。
     */
    public TreeNode {
        sources = sources == null ? List.of() : sources;
    }
}
