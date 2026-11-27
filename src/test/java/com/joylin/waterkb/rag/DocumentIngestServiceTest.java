package com.joylin.waterkb.rag;

import com.joylin.waterkb.config.RagProperties;
import com.joylin.waterkb.vo.UploadResult;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.joylin.waterkb.service.DocumentIngestService;
import com.joylin.waterkb.service.ParagraphSplitter;

/**
 * 多格式文档解析 + 入库链路测试。
 * 用 Mockito 捕获 vectorStore.add() 的参数，验证解析后的文本与元数据，无需真实数据库。
 */
class DocumentIngestServiceTest {

    private static final RagProperties PROPS = new RagProperties();

    private VectorStore vectorStore;
    private DocumentIngestService service;
    private ArgumentCaptor<List<Document>> docsCaptor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        vectorStore = mock(VectorStore.class);
        docsCaptor = ArgumentCaptor.forClass(List.class);
        PROPS.setChunkMaxChars(600);
        PROPS.setChunkOverlap(100);
        PROPS.setEmbeddingBatchSize(10);
        service = new DocumentIngestService(vectorStore, new ParagraphSplitter(PROPS), PROPS);
    }

    /** 构造一个两行两列的 xlsx：表头 站点/水位，数据行 云溪水库/45.1 */
    private byte[] sampleXlsx() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("水情");
            Row header = sheet.createRow(0);
            Cell h0 = header.createCell(0); h0.setCellValue("站点");
            Cell h1 = header.createCell(1); h1.setCellValue("水位");
            Row data = sheet.createRow(1);
            Cell d0 = data.createCell(0); d0.setCellValue("云溪水库");
            Cell d1 = data.createCell(1); d1.setCellValue(45.1);
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void 支持格式列表包含常见办公格式() {
        List<String> formats = DocumentIngestService.getSupportedExtensions().stream().toList();
        assertTrue(formats.containsAll(List.of(
                "txt", "md", "pdf", "doc", "docx", "xls", "xlsx", "csv", "tsv", "pptx", "geojson")));
        assertTrue(formats.size() >= 30, "应覆盖尽可能多的格式，实际：" + formats.size());
    }

    @Test
    void geojson按纯文本解析入库() throws Exception {
        String geo = "{\"type\":\"FeatureCollection\",\"features\":[{\"properties\":{\"name\":\"大王滩水库管理范围线\"}}]}";
        UploadResult result = service.ingest("管理范围线.geojson", geo.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, result.chunks());
        verify(vectorStore).add(docsCaptor.capture());
        assertTrue(docsCaptor.getValue().get(0).getText().contains("大王滩水库"));
    }

    @Test
    void xlsx保留表头字段语义并入向量库() throws Exception {
        UploadResult result = service.ingest("水情.xlsx", sampleXlsx());

        verify(vectorStore).add(docsCaptor.capture());
        String allText = docsCaptor.getValue().stream().map(Document::getText).reduce("", String::concat);
        assertTrue(allText.contains("工作表：水情"), "应保留 sheet 名");
        assertTrue(allText.contains("站点：云溪水库"), "表头应作为字段名：" + allText);
        assertTrue(allText.contains("水位：45.1"), "数值应按显示值提取：" + allText);
        assertEquals("水情.xlsx", result.fileName());
        assertTrue(result.chunks() >= 1);
        // 元数据可用于引用溯源
        assertEquals("水情.xlsx", docsCaptor.getValue().get(0).getMetadata().get("fileName"));
    }

    @Test
    void csv保留表头且正确解析引号转义() throws Exception {
        String csv = "站点,备注\n云溪水库,\"水位 45.1,正常\"\n";
        service.ingest("水情.csv", csv.getBytes(StandardCharsets.UTF_8));

        verify(vectorStore).add(docsCaptor.capture());
        String allText = docsCaptor.getValue().stream().map(Document::getText).reduce("", String::concat);
        assertTrue(allText.contains("站点：云溪水库"), allText);
        assertTrue(allText.contains("水位 45.1,正常"), "引号内的逗号不应被当分隔符：" + allText);
    }

    @Test
    void md文本正常入库() throws Exception {
        String md = "# 调度规程\n\n汛限水位为45.50米。\n\n opens the gate。";
        UploadResult result = service.ingest("规程.md", md.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, result.chunks());
        verify(vectorStore).add(docsCaptor.capture());
        assertTrue(docsCaptor.getValue().get(0).getText().contains("45.50"));
    }

    /** 构造一个含中文段落的 docx，验证 Tika 解析路径 */
    private byte[] sampleDocx() throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph p1 = doc.createParagraph();
            p1.createRun().setText("云溪水库调度规程：汛限水位45.50米。");
            XWPFParagraph p2 = doc.createParagraph();
            p2.createRun().setText("当库水位达到50.00米时，启动防汛橙色预警。");
            doc.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void docx经Tika解析后正常入库() throws Exception {
        UploadResult result = service.ingest("规程.docx", sampleDocx());

        verify(vectorStore).add(docsCaptor.capture());
        String allText = docsCaptor.getValue().stream().map(Document::getText).reduce("", String::concat);
        assertTrue(allText.contains("汛限水位45.50米"), "Tika 应正确抽取 docx 段落文本：" + allText);
        assertTrue(allText.contains("橙色预警"), allText);
        assertTrue(result.chars() > 20);
    }

    @Test
    void 切片数超过批次上限时分批入库() throws Exception {
        // 用极小切片参数构造 >10 个切片，模拟用户上传大文档触发 DashScope batch 限制的场景
        RagProperties small = new RagProperties();
        small.setChunkMaxChars(20);
        small.setChunkOverlap(0);
        small.setEmbeddingBatchSize(5);
        DocumentIngestService batchService = new DocumentIngestService(vectorStore, new ParagraphSplitter(small), small);

        // 20 个短句、空行分隔 → 20 个自然段 → 20 个切片 > 单批 5 条
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("第").append(i).append("段规程内容。\n\n");
        }
        UploadResult result = batchService.ingest("大文档.md", sb.toString().getBytes(StandardCharsets.UTF_8));

        // 贪心聚合后的切片数可能小于自然段数，批次数按 ceil(切片数/批大小) 精确验证
        int expectedBatches = (result.chunks() + small.getEmbeddingBatchSize() - 1) / small.getEmbeddingBatchSize();
        verify(vectorStore, times(expectedBatches)).add(docsCaptor.capture());
        List<List<Document>> batches = docsCaptor.getAllValues();
        // 每批不超过 batch size（5），且总量守恒
        batches.forEach(b -> assertTrue(b.size() <= 5, "单批不得超过 batch size，实际：" + b.size()));
        int total = batches.stream().mapToInt(List::size).sum();
        assertEquals(result.chunks(), total, "分批后的切片总数应与入库结果一致");
        assertTrue(result.chunks() > 5, "应产生多于一个批次的切片数，实际：" + result.chunks());
    }

    @Test
    void 不支持的格式直接拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.ingest("恶意.exe", new byte[]{1, 2, 3}));
        verifyNoInteractions(vectorStore);
    }

    @Test
    void 空文件被拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> service.ingest("空.txt", new byte[0]));
        verifyNoInteractions(vectorStore);
    }
}
