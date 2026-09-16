package io.trustagent.app;

import io.trustagent.app.dto.AppDtos;
import io.trustagent.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 应用与安全策略接口。
 *
 * <p>权限模型：每个方法都有 {@code @PreAuthorize} 声明需要的权限点，
 * 属主级别的细粒度判断在 {@link AgentAppService} 中（ADMIN 与属主可写，AUDITOR 只读）。
 * 两层校验缺一不可：注解管“这个角色能不能调这类接口”，Service 管“这个对象归不归你”。
 */
@RestController
@RequestMapping("/api/apps")
@RequiredArgsConstructor
public class AgentAppController {

    private final AgentAppService agentAppService;

    @GetMapping
    @PreAuthorize("hasAuthority('app:read')")
    public ApiResponse<List<AppDtos.AgentAppView>> list() {
        return ApiResponse.ok(agentAppService.listApps());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('app:create')")
    public ApiResponse<AppDtos.AgentAppView> create(@Valid @RequestBody AppDtos.AgentAppCreateRequest request) {
        return ApiResponse.ok(agentAppService.create(request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('app:read')")
    public ApiResponse<AppDtos.AgentAppView> detail(@PathVariable Long id) {
        return ApiResponse.ok(agentAppService.detail(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('app:update')")
    public ApiResponse<AppDtos.AgentAppView> update(@PathVariable Long id,
                                                    @Valid @RequestBody AppDtos.AgentAppUpdateRequest request) {
        return ApiResponse.ok(agentAppService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('app:delete')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        agentAppService.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/tools")
    @PreAuthorize("hasAuthority('app:read')")
    public ApiResponse<List<AppDtos.ToolView>> tools(@PathVariable Long id) {
        return ApiResponse.ok(agentAppService.listTools(id));
    }

    @PutMapping("/{id}/tools")
    @PreAuthorize("hasAuthority('policy:update')")
    public ApiResponse<List<AppDtos.ToolView>> saveTools(@PathVariable Long id,
                                                         @Valid @RequestBody List<AppDtos.ToolConfigRequest> configs) {
        return ApiResponse.ok(agentAppService.saveTools(id, configs));
    }

    @GetMapping("/{id}/policy")
    @PreAuthorize("hasAuthority('policy:read')")
    public ApiResponse<AppDtos.PolicyView> policy(@PathVariable Long id) {
        return ApiResponse.ok(agentAppService.getPolicy(id));
    }

    @PutMapping("/{id}/policy")
    @PreAuthorize("hasAuthority('policy:update')")
    public ApiResponse<AppDtos.PolicyView> savePolicy(@PathVariable Long id,
                                                      @Valid @RequestBody AppDtos.PolicyRequest request) {
        return ApiResponse.ok(agentAppService.savePolicy(id, request));
    }

    /** 内置工具目录，供前端渲染工具选择器。不涉及任何具体应用的数据。 */
    @GetMapping("/tool-catalog")
    @PreAuthorize("hasAuthority('app:read')")
    public ApiResponse<List<Map<String, Object>>> toolCatalog() {
        List<Map<String, Object>> catalog = ToolCatalog.all().stream()
                .map(name -> Map.<String, Object>of(
                        "toolName", name,
                        "description", ToolCatalog.descriptionOf(name),
                        "sideEffect", ToolCatalog.hasSideEffect(name)))
                .toList();
        return ApiResponse.ok(catalog);
    }
}
