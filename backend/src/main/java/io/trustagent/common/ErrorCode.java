package io.trustagent.common;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码。HTTP 状态码表达协议语义，code 表达业务语义，两者都要给前端。
 *
 * @see <a href="../../../../../../../docs/API.md">docs/API.md 第 9 节</a>
 */
public enum ErrorCode {

    OK(0, HttpStatus.OK, "ok"),

    VALIDATION_FAILED(40001, HttpStatus.BAD_REQUEST, "参数校验失败"),
    INPUT_TOO_LONG(40002, HttpStatus.BAD_REQUEST, "输入超过允许长度"),
    IDEMPOTENCY_KEY_REQUIRED(40003, HttpStatus.BAD_REQUEST, "缺少 Idempotency-Key 请求头"),
    DATASET_IMPORT_FAILED(40004, HttpStatus.BAD_REQUEST, "评测集没有可导入的记录"),

    INTERNAL_TOKEN_INVALID(40100, HttpStatus.UNAUTHORIZED, "内部凭证无效"),
    BAD_CREDENTIALS(40101, HttpStatus.UNAUTHORIZED, "用户名或密码错误"),
    TOKEN_INVALID(40102, HttpStatus.UNAUTHORIZED, "登录状态无效或已过期"),
    ACCOUNT_DISABLED(40103, HttpStatus.UNAUTHORIZED, "账号已被禁用"),

    FORBIDDEN(40301, HttpStatus.FORBIDDEN, "无权访问该资源"),
    ROLE_NOT_ALLOWED(40302, HttpStatus.FORBIDDEN, "当前角色不允许该操作"),

    NOT_FOUND(40401, HttpStatus.NOT_FOUND, "资源不存在"),

    CONFLICT(40901, HttpStatus.CONFLICT, "资源状态冲突，请刷新后重试"),
    DUPLICATE_RESOURCE(40902, HttpStatus.CONFLICT, "资源已存在"),

    RATE_LIMITED(42901, HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁"),

    INTERNAL_ERROR(50001, HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误"),
    AI_SERVICE_UNAVAILABLE(50201, HttpStatus.BAD_GATEWAY, "执行面不可用"),
    AI_SERVICE_TIMEOUT(50401, HttpStatus.GATEWAY_TIMEOUT, "执行面处理超时");

    private final int code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(int code, HttpStatus httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
