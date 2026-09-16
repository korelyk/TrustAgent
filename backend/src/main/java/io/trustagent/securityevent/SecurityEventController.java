package io.trustagent.securityevent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.trustagent.common.ApiResponse;
import io.trustagent.common.PageResult;
import io.trustagent.securityevent.mapper.SecurityEventMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/security/events")
@RequiredArgsConstructor
public class SecurityEventController {

    private final SecurityEventMapper mapper;
    private final SecurityEventService securityEventService;

    public record SecurityEventView(Long id, String traceId, Long appId, String eventType, String severity,
                                    String detector, String ruleId, String evidenceMasked,
                                    String actionTaken, LocalDateTime createdAt) {
    }

    @GetMapping
    @PreAuthorize("hasAuthority('security:read')")
    public ApiResponse<PageResult<SecurityEventView>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) Long appId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {

        long safeSize = Math.min(Math.max(size, 1), 200);
        LambdaQueryWrapper<SecurityEvent> query = Wrappers.<SecurityEvent>lambdaQuery()
                .eq(eventType != null && !eventType.isBlank(), SecurityEvent::getEventType, eventType)
                .eq(severity != null && !severity.isBlank(), SecurityEvent::getSeverity, severity)
                .eq(appId != null, SecurityEvent::getAppId, appId)
                .ge(from != null, SecurityEvent::getCreatedAt, from)
                .le(to != null, SecurityEvent::getCreatedAt, to)
                .orderByDesc(SecurityEvent::getId);

        IPage<SecurityEvent> result = mapper.selectPage(new Page<>(Math.max(page, 1), safeSize), query);
        List<SecurityEventView> records = result.getRecords().stream()
                .map(e -> new SecurityEventView(e.getId(), e.getTraceId(), e.getAppId(), e.getEventType(),
                        e.getSeverity(), e.getDetector(), e.getRuleId(), e.getEvidenceMasked(),
                        e.getActionTaken(), e.getCreatedAt()))
                .toList();
        return ApiResponse.ok(PageResult.of(records, result.getTotal(), result.getCurrent(), result.getSize()));
    }

    /** 仪表盘用：按类型/级别/规则聚合的真实计数（不是写死的数字）。 */
    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('security:read')")
    public ApiResponse<Map<String, Object>> stats() {
        return ApiResponse.ok(securityEventService.stats());
    }
}
