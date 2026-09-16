package io.trustagent.user;

import io.trustagent.audit.AuditService;
import io.trustagent.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户与角色管理，仅 ADMIN 可用（权限点 {@code user:manage}）。
 *
 * <p>这三条接口也是“越权测试”的主要对象：DEVELOPER 与 AUDITOR 调用必须 403。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserAdminController {

    private final UserAccountService userAccountService;
    private final AuditService auditService;

    public record CreateUserRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(min = 8, max = 128) String password,
            @Size(max = 64) String displayName,
            @Size(max = 128) String email,
            @NotEmpty List<String> roles) {
    }

    public record UpdateRolesRequest(@NotEmpty List<String> roles) {
    }

    public record UpdateStatusRequest(@NotBlank String status) {
    }

    @GetMapping
    @PreAuthorize("hasAuthority('user:manage')")
    public ApiResponse<List<UserAccountService.UserView>> list() {
        return ApiResponse.ok(userAccountService.listAll().stream().map(userAccountService::toView).toList());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('user:manage')")
    public ApiResponse<UserAccountService.UserView> create(@Valid @RequestBody CreateUserRequest request) {
        SysUser user = userAccountService.createUser(request.username(), request.password(),
                request.displayName(), request.email(), request.roles());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("username", user.getUsername());
        detail.put("roles", request.roles());
        auditService.record("USER_CREATED", "user", String.valueOf(user.getId()), detail);
        return ApiResponse.ok(userAccountService.toView(user));
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('user:manage')")
    public ApiResponse<UserAccountService.UserView> updateRoles(@PathVariable Long id,
                                                               @Valid @RequestBody UpdateRolesRequest request) {
        SysUser user = userAccountService.findById(id);
        if (user == null) {
            throw io.trustagent.common.BizException.notFound("用户不存在: " + id);
        }
        List<String> before = userAccountService.roleCodes(id);
        userAccountService.replaceRoles(id, request.roles());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("username", user.getUsername());
        detail.put("rolesBefore", before);
        detail.put("rolesAfter", request.roles());
        auditService.record("USER_ROLES_CHANGED", "user", String.valueOf(id), detail);
        return ApiResponse.ok(userAccountService.toView(user));
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('user:manage')")
    public ApiResponse<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody UpdateStatusRequest request) {
        userAccountService.setStatus(id, request.status());
        auditService.record("USER_STATUS_CHANGED", "user", String.valueOf(id),
                Map.of("status", request.status()));
        return ApiResponse.ok();
    }
}
