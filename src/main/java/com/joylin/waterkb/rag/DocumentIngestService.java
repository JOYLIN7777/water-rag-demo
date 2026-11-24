package com.joylin.waterkb.rag;

import com.joylin.waterkb.config.RagProperties;
import com.joylin.waterkb.web.dto.UploadResult;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文档入库服务：解析 → 切片 → 向量化 → 落 pgvector。
 *
 * <p>解析策略按语料类型分三路（设计决策见 README）：
 * <ul>
 *   <li>纯文本（txt/md/json/xml/yaml/sql/log 等）：UTF-8 直接读取，零解析开销；</li>
 *   <li>表格（xls/xlsx/csv/tsv）：POI/手写 CSV 解析，保留"表头当字段名"语义，
 *       否则表格进了向量库只剩一堆没有列名的值，检索时无法判断含义；</li>
 *   <li>其他所有格式（pdf/doc/docx/ppt/pptx/rtf/odt/html/epub 等）：Apache Tika
 *       统一自动检测解析。一个库覆盖几十种格式，比逐个引入 PDFBox/POI 分支更好维护。</li>
 * </ul></p>
 *
 * <p>vectorStore.add() 内部会调用配置的 EmbeddingModel 批量向量化，
 * 再写入 pgvector 的 vector_store 表并维护 HNSW 索引，我们只管喂文本。</p>
 */
@Service
public class DocumentIngestService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestService.class);

    static {
        // POI 默认拒绝分配单条超过 100MB 的记录（RecordFormatException），
        // 数字孪生项目 pptx 实测存在 105MB 的单条记录（大图/嵌入对象），放宽到 1.5GB。
        // 代价是解析超大文件时可能临时占用大块堆内存，容器堆默认 25% 物理内存，可承受。
        org.apache.poi.util.IOUtils.setByteArrayMaxOverride(1_500_000_000);
    }

    /** 纯文本类：直接按 UTF-8 读，无需解析器（geojson/topojson 本质是 JSON 文本） */
    private static final Set<String> PLAIN_TEXT_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "json", "geojson", "topojson", "xml", "yaml", "yml",
            "sql", "log", "ini", "properties", "java", "py", "js", "ts", "css");

    /** 表格类：走表头语义解析（csv/tsv 手写，xls/xlsx 走 POI） */
    private static final Set<String> TABLE_EXTENSIONS = Set.of("xls", "xlsx", "csv", "tsv");

    /** Tika 解析类：富文本与办公文档 */
    private static final Set<String> TIKA_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "ppt", "pptx", "rtf", "odt", "ods", "odp",
            "html", "htm", "epub", "msg", "eml");

    /** 单工作表最多读取行数，防止超大表格把内存打爆 */
    private static final int MAX_SHEET_ROWS = 5000;

    /** 单元格文本最长保留字符数 */
    private static final int MAX_CELL_CHARS = 500;

    private static final DataFormatter DATA_FORMATTER = new DataFormatter(Locale.CHINA);
    private static final Parser TIKA_PARSER = new AutoDetectParser();

    private final VectorStore vectorStore;
    private final ParagraphSplitter splitter;
    private final RagProperties props;

    public DocumentIngestService(VectorStore vectorStore, ParagraphSplitter splitter, RagProperties props) {
        this.vectorStore = vectorStore;
        this.splitter = splitter;
        this.props = props;
    }

    /** 对外暴露支持的格式集合（供接口层校验与前端展示，避免两处硬编码不一致） */
    public static Set<String> getSupportedExtensions() {
        Set<String> all = new LinkedHashSet<>(PLAIN_TEXT_EXTENSIONS);
        all.addAll(TABLE_EXTENSIONS);
        all.addAll(TIKA_EXTENSIONS);
        return all;
    }

    /** 兼容旧调用（单测/演示脚本）：默认可见范围=本部门+上级，不下发子树 */
    public UploadResult ingest(String fileName, byte[] bytes) throws IOException {
        return ingest(fileName, bytes, false);
    }

    /**
     * 解析文档并写入向量库。
     *
     * @param fileName       原始文件名（决定解析方式）
     * @param bytes          文件字节内容
     * @param includeSubtree 可见范围是否含下级部门：false=本部门+上级可见；
     *                       true=再向下下发到整个子树（兄弟部门依然不可见）
     * @return 入库结果（文件名、切片数、字符数）
     */
    public UploadResult ingest(String fileName, byte[] bytes, boolean includeSubtree) throws IOException {
        String text = extractText(fileName, bytes);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("文件解析结果为空：可能是扫描版 PDF/图片（需 OCR），或文件内容本身为空");
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
            // 行级权限：上传者为 owner；可见性由部门规则（tenantId + visibleSubtree）决定
            // allowedGroups 仅作元数据留存，检索过滤不再消费它（功能角色组不是数据可见组）
            com.joylin.waterkb.security.UserContext.CurrentUser user = com.joylin.waterkb.security.UserContext.get();
            meta.put("ownerUser", user.userId());
            // 部门知识库隔离：文档归属上传者的部门（org_key），
            // 可见性 = 本部门 ∪ 上级部门（默认） ∪ 下级子树（勾选下发），见 EsHybridSearchService
            meta.put("tenantId", user.tenantId());
            meta.put("visibleSubtree", includeSubtree);
            java.util.List<String> groups = new java.util.ArrayList<>(user.groups());
            if (groups.isEmpty()) groups.add("public");
            meta.put("allowedGroups", groups);
            docs.add(new Document(chunks.get(i), meta));
        }

        // 分批向量化入库：DashScope embedding 接口限制单次请求最多 10 条文本，
        // 一次性全量提交会报 400（batch size is invalid），必须按批切分
        int batchSize = Math.max(1, props.getEmbeddingBatchSize());
        for (int start = 0; start < docs.size(); start += batchSize) {
            int end = Math.min(start + batchSize, docs.size());
            vectorStore.add(docs.subList(start, end));
        }
        log.info("文档入库完成：{}，共 {} 个切片（{} 批），{} 字符",
                fileName, chunks.size(), (chunks.size() + batchSize - 1) / batchSize, text.length());
        return new UploadResult(fileName, chunks.size(), text.length());
    }

    /**
     * 按扩展名分派解析器。
     */
    private String extractText(String fileName, byte[] bytes) throws IOException {
        String ext = extension(fileName);
        if (PLAIN_TEXT_EXTENSIONS.contains(ext)) {
            return readPlainText(bytes);
        }
        if (TABLE_EXTENSIONS.contains(ext)) {
            return switch (ext) {
                case "xls", "xlsx" -> extractExcel(bytes, ext);
                default -> extractDelimited(bytes, ext);
            };
        }
        if (TIKA_EXTENSIONS.contains(ext)) {
            return extractByTika(fileName, bytes);
        }
        throw new IllegalArgumentException("暂不支持的文件格式：." + ext);
    }

    /**
     * 纯文本：UTF-8 解码并去掉 BOM（Windows 记事本保存的文件常带 BOM）。
     */
    private String readPlainText(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /**
     * Excel 解析（xls/xlsx）：每个 sheet 单独成段，首行做表头，
     * 后续每行拼成"列名：值，列名：值"，保留表格字段语义便于向量检索命中。
     */
    private String extractExcel(byte[] bytes, String ext) throws IOException {
        Workbook workbook;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            workbook = "xlsx".equals(ext) ? new XSSFWorkbook(in) : new HSSFWorkbook(in);
        }
        StringBuilder sb = new StringBuilder();
        try (workbook) {
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                sb.append("【工作表：").append(sheet.getSheetName()).append("】\n");
                List<String> headers = null;
                int rowCount = 0;
                for (Row row : sheet) {
                    if (rowCount++ >= MAX_SHEET_ROWS) {
                        sb.append("（该工作表行数超过 ").append(MAX_SHEET_ROWS).append("，已截断）\n");
                        break;
                    }
                    List<String> values = readRow(row);
                    if (values.stream().allMatch(String::isBlank)) {
                        continue; // 跳过全空行
                    }
                    if (headers == null) {
                        headers = values; // 第一行非空行作为表头
                        continue;
                    }
                    sb.append(joinRow(headers, values)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /**
     * CSV/TSV 解析：同样保留表头语义。手写简易解析器（支持引号包裹与转义双引号），
     * 避免为一个 Demo 再引入 commons-csv 依赖。
     */
    private String extractDelimited(byte[] bytes, String ext) throws IOException {
        char sep = "tsv".equals(ext) ? '\t' : ',';
        String content = readPlainText(bytes);
        List<String> headers = null;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new java.io.StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> values = parseDelimitedLine(line, sep);
                if (values.stream().allMatch(String::isBlank)) {
                    continue;
                }
                if (headers == null) {
                    headers = values;
                    continue;
                }
                sb.append(joinRow(headers, values)).append('\n');
            }
        }
        return sb.toString();
    }

    /** 带引号的 CSV 行解析（"" 表示一个字面双引号） */
    private List<String> parseDelimitedLine(String line, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"'); // 转义双引号
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == sep && !inQuotes) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString().trim());
        return out;
    }

    /** 读取一行所有单元格（用 DataFormatter 保留数字/日期的显示值） */
    private List<String> readRow(Row row) {
        List<String> out = new ArrayList<>();
        int last = Math.min(row.getLastCellNum(), 100); // 最多读 100 列
        for (int i = 0; i < last; i++) {
            Cell cell = row.getCell(i);
            String value = cell == null ? "" : DATA_FORMATTER.formatCellValue(cell);
            if (value.length() > MAX_CELL_CHARS) {
                value = value.substring(0, MAX_CELL_CHARS) + "…";
            }
            out.add(value.trim());
        }
        return out;
    }

    /** 表头 + 值行拼接为"列名：值"文本 */
    private String joinRow(List<String> headers, List<String> values) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).isBlank()) {
                continue;
            }
            String header = i < headers.size() && !headers.get(i).isBlank() ? headers.get(i) : "列" + (i + 1);
            if (line.length() > 0) {
                line.append("，");
            }
            line.append(header).append("：").append(values.get(i));
        }
        return line.toString();
    }

    /**
     * Tika 统一解析：pdf/doc/docx/ppt/pptx/rtf/odt/html/epub 等富文档。
     * 文件名通过 Metadata.RESOURCE_NAME_KEY 传入，帮助 Tika 更准确地检测格式。
     */
    private String extractByTika(String fileName, byte[] bytes) throws IOException {
        BodyContentHandler handler = new BodyContentHandler(-1); // -1 = 不限制输出长度
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            TIKA_PARSER.parse(in, handler, metadata, new ParseContext());
        } catch (SAXException | TikaException e) {
            throw new IOException("文档解析失败：" + fileName + "（" + e.getMessage() + "）", e);
        }
        return handler.toString();
    }

    private String extension(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i < 0 ? "" : fileName.substring(i + 1).toLowerCase(Locale.ROOT);
    }
}
