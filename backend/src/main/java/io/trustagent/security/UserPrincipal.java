package io.trustagent.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 认证主体。
 *
 * <p>权限集合包含两类 authority：
 * <ul>
 *   <li>权限点，如 {@code app:create} —— 业务接口用它做 {@code @PreAuthorize}</li>
 *   <li>角色，如 {@code ROLE_ADMIN} —— 便于整体性判断（例如“ADMIN 能看全部资源”）</li>
 * </ul>
 */
public class UserPrincipal implements UserDetails {

    private final Long id;
    private final String username;
    private final String displayName;
    private final String status;
    private final List<String> roles;
    private final List<String> permissions;
    private final List<GrantedAuthority> authorities;

    public UserPrincipal(Long id, String username, String displayName, String status,
                         List<String> roles, List<String> permissions) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.status = status;
        this.roles = List.copyOf(roles);
        this.permissions = List.copyOf(permissions);
        this.authorities = java.util.stream.Stream.concat(
                        permissions.stream().map(SimpleGrantedAuthority::new),
                        roles.stream().map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r)))
                .toList();
    }

    public Long getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<String> getRoles() {
        return roles;
    }

    public List<String> getPermissions() {
        return permissions;
    }

    public boolean isAdmin() {
        return roles.contains("ADMIN");
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    /** 控制面不保存用户密码到会话中（无状态 JWT），因此此处返回空。 */
    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return "ACTIVE".equals(status);
    }
}
