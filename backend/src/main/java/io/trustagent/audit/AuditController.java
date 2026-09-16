package io.trustagent.audit;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.trustagent.audit.mapper.AuditLogMapper;
import io.trustagent.common.ApiResponse;
import io.trustagent.common.PageResult;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditLogMapper auditLogMapper;
    private final AuditVerificationService auditVerificationService;

    public record AuditLogView(Long seq, String actorUsername, String action, String resourceType,
                               String resourceId, String detailJson, String traceId, String prevHash,
                               String recordHash, LocalDateTime createdAt) {
    }

    /** 审计日志分页。只有具备 audit:read 的角色（ADMIN / AUDITOR）才能查看。 */
    @GetMapping("/logs")
    @PreAuthorize("hasAuthority('audit:read')")
    public ApiResponse<PageResult<AuditLogView>> logs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String action) {
        long safeSize = Math.min(Math.max(size, 1), 200);
        IPage<AuditLog> result = auditLogMapper.selectPage(
                new Page<>(Math.max(page, 1), safeSize),
                Wrappers.<AuditLog>lambdaQuery()
                        .eq(action != null && !action.isBlank(), AuditLog::getAction, action)
                        .orderByAsc(AuditLog::getSeq));
        List<AuditLogView> records = result.getRecords().stream()
                .map(e -> new AuditLogView(e.getSeq(), e.getActorUsername(), e.getAction(),
                        e.getResourceType(), e.getResourceId(), e.getDetailJson(), e.getTraceId(),
                        e.getPrevHash(), e.getRecordHash(), e.getCreatedAt()))
                .toList();
        return ApiResponse.ok(PageResult.of(records, result.getTotal(), result.getCurrent(), result.getSize()));
    }

    /**
     * 验证哈希链完整性。失败时返回首个异常位置与原因，便于定位被篡改的记录。
     * 返回 HTTP 200 且 {@code valid=false}：这是“验证已完成”，不是接口错误。
     */
    @GetMapping("/verify")
    @PreAuthorize("hasAuthority('audit:verify')")
    public ApiResponse<AuditVerificationService.VerifyResult> verify() {
        return ApiResponse.ok(auditVerificationService.verify());
    }
}
