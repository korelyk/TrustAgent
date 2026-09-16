package io.trustagent.common;

import io.trustagent.ratelimit.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 统一异常出口：任何异常都以 {@link ApiResponse} 形式返回，且日志里带上 traceId。
 *
 * <p>原则：不把堆栈、SQL、类名直接暴露给调用方，只返回可读的 message；
 * 细节写日志（含 traceId），便于排查。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Object>> handleBiz(BizException ex, HttpServletRequest request) {
        ErrorCode code = ex.errorCode();
        if (code.httpStatus().is5xxServerError()) {
            log.error("业务异常 path={} code={} msg={}", request.getRequestURI(), code.code(), ex.getMessage(), ex);
        } else {
            log.info("业务异常 path={} code={} msg={}", request.getRequestURI(), code.code(), ex.getMessage());
        }
        return ResponseEntity.status(code.httpStatus()).body(ApiResponse.fail(code, ex.getMessage(), ex.details()));
    }

    /** 限流是 BizException 的子类，Spring 会选择更精确的处理器，因此这里能单独加 Retry-After 头。 */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiResponse<Object>> handleRateLimited(RateLimitExceededException ex) {
        return ResponseEntity.status(ErrorCode.RATE_LIMITED.httpStatus())
                .header("Retry-After", String.valueOf(ex.retryAfterSeconds()))
                .body(ApiResponse.fail(ErrorCode.RATE_LIMITED, ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleInvalid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.httpStatus())
                .body(ApiResponse.fail(ErrorCode.VALIDATION_FAILED, detail));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Object>> handleMissingHeader(MissingRequestHeaderException ex) {
        ErrorCode code = "Idempotency-Key".equalsIgnoreCase(ex.getHeaderName())
                ? ErrorCode.IDEMPOTENCY_KEY_REQUIRED
                : ErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(code.httpStatus())
                .body(ApiResponse.fail(code, "缺少请求头 " + ex.getHeaderName()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        log.info("请求体无法解析: {}", ex.getMessage());
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.httpStatus())
                .body(ApiResponse.fail(ErrorCode.VALIDATION_FAILED, "请求体不是合法 JSON 或字段类型错误"));
    }

    /**
     * 方法级 {@code @PreAuthorize} 拒绝时抛出的异常。这里统一映射为 403，
     * 但**故意不返回**“你缺少哪个权限”的细节，避免给越权探测者提供权限地图。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Object>> handleDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("越权访问被拒绝 path={} user={} msg={}", request.getRequestURI(),
                SecurityUtilsHolder.usernameOrAnonymous(), ex.getMessage());
        return ResponseEntity.status(ErrorCode.FORBIDDEN.httpStatus())
                .body(ApiResponse.fail(ErrorCode.FORBIDDEN, "无权访问该资源"));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Object>> handleDuplicate(DuplicateKeyException ex) {
        log.info("唯一约束冲突: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(ErrorCode.DUPLICATE_RESOURCE.httpStatus())
                .body(ApiResponse.fail(ErrorCode.DUPLICATE_RESOURCE, "资源已存在（唯一约束冲突）"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Object>> handleTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.httpStatus())
                .body(ApiResponse.fail(ErrorCode.VALIDATION_FAILED, "上传内容超过大小限制"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleOther(Exception ex, HttpServletRequest request) {
        log.error("未处理异常 path={}", request.getRequestURI(), ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR, "服务内部错误，请提供 traceId 联系管理员"));
    }
}
