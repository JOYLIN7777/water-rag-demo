package com.joylin.waterkb.web;

import com.joylin.waterkb.rag.DocumentIngestService;
import com.joylin.waterkb.web.dto.UploadResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Set;

/**
 * 文档上传接口。
 */
@RestController
@RequestMapping("/api/docs")
public class DocumentController {

    /** 支持的文件类型 */
    private static final Set<String> ALLOWED = Set.of("txt", "md", "pdf");

    private final DocumentIngestService ingestService;

    public DocumentController(DocumentIngestService ingestService) {
        this.ingestService = ingestService;
    }

    /**
     * 上传文档：解析 → 切片 → 向量化入库。
     * curl 示例见 README。
     */
    @PostMapping("/upload")
    public UploadResult upload(@RequestParam("file") MultipartFile file) throws IOException {
        String name = file.getOriginalFilename();
        if (name == null || !ALLOWED.contains(extension(name))) {
            throw new IllegalArgumentException("仅支持 txt / md / pdf 文件");
        }
        return ingestService.ingest(name, file.getBytes());
    }

    private String extension(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }
}
