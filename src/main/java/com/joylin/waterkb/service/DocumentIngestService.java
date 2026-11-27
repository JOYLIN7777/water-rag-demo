package com.joylin.waterkb.service;

import com.joylin.waterkb.config.RagProperties;
import com.joylin.waterkb.vo.UploadResult;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.joylin.waterkb.security.UserContext;

/**
 * 文档入库服务：解析 → 切片 → 向量化 → 落 pgvector。
 *
 *
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

    /**
     * 纯文本类：直接按 UTF-8 读，无需解析器（geojson/topojson 本质是 JSON 文本）
     */
    private static final Set<String> PLAIN_TEXT_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "json", "geojson", "topojson", "xml", "yaml", "yml",
            "sql", "log", "ini", "properties", "java", "py", "js", "ts", "css");

    /**
     * 表格类：走表头语义解析（csv/tsv 手写，xls/xlsx 走 POI）
     */
    private static final Set<String> TABLE_EXTENSIONS = Set.of("xls", "xlsx", "csv", "tsv");

    /**
     * Tika 解析类：富文本与办公文档
     */
    private static final Set<String> TIKA_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "ppt", "pptx", "rtf", "odt", "ods", "odp",
            "html", "htm", "epub", "msg", "eml");

    /**
     * 图片：Tika 拿不到文本，走 OCR
     */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "bmp", "tif", "tiff", "gif", "webp");

    /**
     * 单工作表最多读取行数，防止超大表格把内存打爆
     */
    private static final int MAX_SHEET_ROWS = 5000;

    /**
     * 单元格文本最长保留字符数
     */
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

    /**
     * 对外暴露支持的格式集合（供接口层校验与前端展示，避免两处硬编码不一致）
     */
    public static Set<String> getSupportedExtensions() {
        Set<String> all = new LinkedHashSet<>(PLAIN_TEXT_EXTENSIONS);
        all.addAll(TABLE_EXTENSIONS);
        all.addAll(TIKA_EXTENSIONS);
        all.addAll(IMAGE_EXTENSIONS);   // 图片走 OCR，也列进支持格式
        return all;
    }

    /**
     * 兼容旧调用（单测/演示脚本）：默认可见范围=本部门+上级，不下发子树
     */
    public UploadResult ingest(String fileName, byte[] bytes) throws IOException {
        return ingest(fileName, bytes, false);
    }

    /**
     * 解析文档并写入向量库。
     *
     * @return 入库结果（文件名、切片数、字符数）
     */
    public UploadResult ingest(String fileName, byte[] bytes, boolean includeSubtree) throws IOException {
        String ext = extension(fileName);
        String text = extractText(fileName, bytes);
        // 扫描版 PDF / 图片没有文本层，Tika 解析为空 → OCR 兜底
        if (text == null || text.isBlank()) {
            if ("pdf".equals(ext) || isImageExt(ext)) {
                text = ocr(fileName, bytes, ext);
            }
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("文件解析结果为空：不是可识别的扫描件，或文件内容本身为空");
        }

        List<String> chunks = splitter.split(text);
        List<Document> docs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> meta = new HashMap<>();
            // 元数据随向量一起入库，检索时可回传给前端做"引用溯源"
            meta.put("fileName", fileName);
            meta.put("chunkIndex", i);
            meta.put("totalChunks", chunks.size());
            // 写入带 Z 的 UTC 绝对时间：ES 的 date 字段按 UTC 解释，
            // 若写无时区的本地时间会被当成 UTC，前端再转本地就多出 8 小时（实测踩坑）。
            meta.put("uploadedAt", Instant.now().toString());
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
        if (IMAGE_EXTENSIONS.contains(ext)) return "";   // 图片交给 OCR
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

    /**
     * 带引号的 CSV 行解析（"" 表示一个字面双引号）
     */
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

    /**
     * 读取一行所有单元格（用 DataFormatter 保留数字/日期的显示值）
     */
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

    /**
     * 表头 + 值行拼接为"列名：值"文本
     */
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

    private boolean isImageExt(String ext) { return IMAGE_EXTENSIONS.contains(ext); }

    /**
     * OCR 兜底：扫描版 PDF 先用 pdftoppm 转图片，图片直接落临时文件，
     * 再统一交给 tesseract（chi_sim+eng）识别。容器里已安装这两个工具。
     */
    private String ocr(String fileName, byte[] bytes, String ext) {
        java.nio.file.Path dir = null;
        try {
            dir = java.nio.file.Files.createTempDirectory("rag-ocr-");
            java.nio.file.Path src = dir.resolve("input." + ext);
            java.nio.file.Files.write(src, bytes);
            java.util.List<java.nio.file.Path> images = new java.util.ArrayList<>();
            if ("pdf".equals(ext)) {
                // pdftoppm -r 200 -png input.pdf page → page-1.png / page-2.png …
                run(dir, "pdftoppm", "-r", "200", "-png", src.toString(), dir.resolve("page").toString());
                try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(dir)) {
                    s.filter(p -> p.getFileName().toString().startsWith("page-")
                            && p.getFileName().toString().endsWith(".png")).forEach(images::add);
                }
            } else {
                images.add(src);
            }
            images.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));
            StringBuilder sb = new StringBuilder();
            for (java.nio.file.Path img : images) {
                String txt = run(dir, "tesseract", img.toString(), "stdout", "-l", "chi_sim+eng", "--psm", "6");
                if (txt != null && !txt.isBlank()) sb.append(txt).append('\n');
                // 扫描件页数多时限制总量，避免一次上传卡死
                if (sb.length() > 2_000_000) break;
            }
            log.info("OCR 完成：{}，识别 {} 页 / {} 字", fileName, images.size(), sb.length());
            return sb.toString();
        } catch (Exception e) {
            log.warn("OCR 失败：{}（{}）", fileName, e.getMessage());
            return "";
        } finally {
            if (dir != null) {
                try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(dir)) {
                    s.forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {} });
                } catch (Exception ignored) { }
                try { java.nio.file.Files.deleteIfExists(dir); } catch (Exception ignored) { }
            }
        }
    }

    /**
     * 执行外部命令并取 stdout；非 0 退出码视为失败
     */
    private String run(java.nio.file.Path dir, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        Process proc = pb.start();
        String out = new String(proc.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int code = proc.waitFor();
        if (code != 0) throw new IllegalStateException(cmd[0] + " 退出码 " + code + "：" + out.trim());
        return out;
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
