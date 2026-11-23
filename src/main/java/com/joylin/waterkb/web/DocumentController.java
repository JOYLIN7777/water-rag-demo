package com.joylin.waterkb.web;

import com.joylin.waterkb.rag.DocumentIngestService;
import com.joylin.waterkb.rag.VectorStoreAdminRepository;
import com.joylin.waterkb.security.Permission;
import com.joylin.waterkb.security.RequirePermission;
import com.joylin.waterkb.web.dto.UploadResult;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文档上传接口。
 */
@RestController
@RequestMapping("/api/docs")
public class DocumentController {

    private final DocumentIngestService ingestService;
    private final VectorStoreAdminRepository adminRepository;
    private final MultipartProperties multipartProperties;

    public DocumentController(DocumentIngestService ingestService,
                              VectorStoreAdminRepository adminRepository,
                              MultipartProperties multipartProperties) {
        this.ingestService = ingestService;
        this.adminRepository = adminRepository;
        this.multipartProperties = multipartProperties;
    }

    /**
     * 上传文档：解析 → 切片 → 向量化入库。
     * 支持的格式与解析服务共用同一份定义（避免两处硬编码不一致）。
     */
    @PostMapping("/upload")
    @RequirePermission(Permission.DOC_WRITE)
    public UploadResult upload(@RequestParam("file") MultipartFile file) throws IOException {
        String name = file.getOriginalFilename();
        Set<String> allowed = DocumentIngestService.getSupportedExtensions();
        if (name == null || !allowed.contains(extension(name))) {
            throw new IllegalArgumentException("不支持的文件格式，当前支持：" + String.join(" / ", allowed));
        }
        return ingestService.ingest(name, file.getBytes());
    }

    /**
     * 查询支持的格式列表（前端上传区动态展示 + 文件大小前端预检）。
     */
    @GetMapping(value = "/formats", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> formats() {
        List<String> list = DocumentIngestService.getSupportedExtensions().stream().sorted().toList();
        return Map.of("extensions", list, "count", list.size(),
                "maxFileSizeBytes", multipartProperties.getMaxFileSize().toBytes());
    }

    /**
     * 知识库文档清单（按文件名聚合：切片数、最近上传时间）。
     */
    @GetMapping(value = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission(Permission.DOC_READ)
    public List<Map<String, Object>> list() {
        return adminRepository.listDocuments();
    }

    /**
     * 删除单个知识文档（按文件名删除其全部切片）。
     */
    @DeleteMapping
    @RequirePermission(Permission.DOC_DELETE)
    public Map<String, Object> delete(@RequestParam String fileName) {
        int deleted = adminRepository.deleteByFileName(fileName);
        if (deleted == 0) {
            throw new IllegalArgumentException("知识库中不存在文档：" + fileName);
        }
        return Map.of("fileName", fileName, "deletedChunks", deleted);
    }

    private String extension(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }
}
