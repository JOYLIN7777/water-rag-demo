-- pgvector 扩展（pgvector/pgvector 镜像自带扩展文件，首次建库时启用即可）
-- 向量表 vector_store 由 Spring AI 的 initialize-schema 自动创建，无需手建
CREATE EXTENSION IF NOT EXISTS vector;
