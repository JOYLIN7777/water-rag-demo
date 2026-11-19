package com.joylin.waterkb.rag;

import com.joylin.waterkb.web.dto.UploadResult;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档入库服务：解析 → 切片 → 向量化 → 落 pgvector。
 *
 * <p>vectorStore.add() 内部会调用配置的 EmbeddingModel 批量向量化，
 * 再写入 pgvector 的 vector_store 表并维护 HNSW 索引，我们只管喂文本。</p>
 */
@Service
public class DocumentIngestService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestService.class);

    private final VectorStore vectorStore;
    private final ParagraphSplitter splitter;

    public DocumentIngestService(VectorStore vectorStore, ParagraphSplitter splitter) {
        this.vectorStore = vectorStore;
        this.splitter = splitter;
    }

    /**
     * 解析文档并写入向量库。
     *
     * @param fileName 原始文件名（决定解析方式）
     * @param bytes    文件字节内容
     * @return 入库结果（文件名、切片数、字符数）
     */
    public UploadResult ingest(String fileName, byte[] bytes) throws IOException {
        String text = extractText(fileName, bytes);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("文件解析结果为空，请确认文件内容有效（扫描版 PDF 暂不支持，需 OCR）");
        }

        List<String> chunks = splitter.split(text);
        List<Document> docs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> meta = new HashMap<>();
            // 元数据随向量一起入库，检索时可回传给前端做"引用溯源"
            meta.put("fileName", fileName);
            meta.put("chunkIndex", i);
            meta.put("totalChunks", chunks.size());
            meta.put("uploadedAt", LocalDateTime.now().toString());
            docs.add(new Document(chunks.get(i), meta));
        }

        vectorStore.add(docs);
        log.info("文档入库完成：{}，共 {} 个切片，{} 字符", fileName, chunks.size(), text.length());
        return new UploadResult(fileName, chunks.size(), text.length());
    }

    /**
     * 按扩展名抽取文本：pdf 走 PDFBox，txt/md 统一按 UTF-8 读取。
     */
    private String extractText(String fileName, byte[] bytes) throws IOException {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            try (PDDocument doc = Loader.loadPDF(bytes)) {
                return new PDFTextStripper().getText(doc);
            }
        }
        // txt / md
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
