package com.joylin.waterkb.vo;

/**
 * 文档上传入库结果。
 *
 */
public record UploadResult(String fileName, int chunks, int chars) {
}
