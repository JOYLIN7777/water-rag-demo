package com.joylin.waterkb.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 向量库管理操作（文档清单 / 按文件名删除切片）。
 *
 * <p>设计决策：直接用 JdbcTemplate 操作 Spring AI 的 vector_store 表，而不是绕
 * VectorStore 接口——后者只支持按 id 删除，而我们的删除维度是"文件名"（存在
 * metadata JSON 里），SQL 一条搞定且语义清晰。表名固定为 pgvector 默认的
 * vector_store（与 application.yml 的 initialize-schema 建表一致）。</p>
 */
@Repository
public class VectorStoreAdminRepository {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreAdminRepository.class);

    private final JdbcTemplate jdbc;

    public VectorStoreAdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 知识库文档清单：按文件名聚合切片数与最近上传时间。
     * 注意 PG 列别名必须加双引号，否则会折叠成小写导致前端取不到 key。
     */
    public List<Map<String, Object>> listDocuments() {
        String sql = "SELECT metadata->>'fileName' AS \"fileName\", COUNT(*) AS \"chunks\", " +
                "MAX(metadata->>'uploadedAt') AS \"uploadedAt\" " +
                "FROM vector_store GROUP BY metadata->>'fileName' ORDER BY \"fileName\"";
        return jdbc.queryForList(sql);
    }

    /**
     * 删除指定文档的所有切片，返回删除条数（0 表示文档不存在）。
     */
    public int deleteByFileName(String fileName) {
        int deleted = jdbc.update("DELETE FROM vector_store WHERE metadata->>'fileName' = ?", fileName);
        log.info("删除文档 {} 的 {} 个切片", fileName, deleted);
        return deleted;
    }
}
