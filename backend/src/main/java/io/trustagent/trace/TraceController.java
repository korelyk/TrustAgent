package io.trustagent.trace;

import io.trustagent.common.ApiResponse;
import io.trustagent.common.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/traces")
@RequiredArgsConstructor
public class TraceController {

    private final TraceService traceService;

    @GetMapping("/{traceId}")
    @PreAuthorize("hasAuthority('trace:read')")
    public ApiResponse<TraceService.TraceDetail> detail(@PathVariable String traceId) {
        return ApiResponse.ok(traceService.detail(traceId));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('trace:read')")
    public ApiResponse<PageResult<AgentTrace>> list(@RequestParam(required = false) Long appId,
                                                    @RequestParam(defaultValue = "1") long page,
                                                    @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(traceService.list(appId, page, size));
    }
}
