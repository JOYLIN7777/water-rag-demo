package com.joylin.waterkb.web.dto;

/**
 * 文档上传入库结果。
 *
 * @param fileName 文件名
 * @param chunks   切片数量（即入库的向量条数）
 * @param chars    原文字符数
 */
public record UploadResult(String fileName, int chunks, int chars) {
}
