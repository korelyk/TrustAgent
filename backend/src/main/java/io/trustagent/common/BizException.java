package io.trustagent.common;

/** 可预期的业务异常，由 {@link GlobalExceptionHandler} 统一转换为响应体。 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Object details;

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null);
    }

    public BizException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BizException(ErrorCode errorCode, String message, Object details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    public static BizException notFound(String message) {
        return new BizException(ErrorCode.NOT_FOUND, message);
    }

    public static BizException forbidden(String message) {
        return new BizException(ErrorCode.FORBIDDEN, message);
    }

    public static BizException badRequest(String message) {
        return new BizException(ErrorCode.VALIDATION_FAILED, message);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Object details() {
        return details;
    }
}
