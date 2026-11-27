package com.joylin.waterkb.common;

/**
 * 统一错误码：后端所有错误响应都引用这里的常量，前端按同一张表映射文案。
 *
 * <p>响应体形状保持 {code, message} 不变（既有前端已依赖），
 * 这里只是把散落的 magic number 收编成编译期可约束的常量。</p>
 */
public final class ErrorCode {
    public static final int INVALID_ARGUMENT = 400;
    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;
    public static final int NOT_FOUND = 404;
    public static final int UPLOAD_TOO_LARGE = 413;
    public static final int INTERNAL = 500;
    public static final int AI_UPSTREAM = 502;
    public static final int SERVICE_UNAVAILABLE = 503;

    /** 工具业务拒绝码：应用层错误，HTTP 仍为 200，靠此码区分 */
    public static final String TOOL_PERMISSION_DENIED = "PERMISSION_DENIED";

    private ErrorCode() { }
}
