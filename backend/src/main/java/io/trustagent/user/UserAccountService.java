package io.trustagent.user;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.trustagent.common.BizException;
import io.trustagent.common.ErrorCode;
import io.trustagent.security.UserPrincipal;
import io.trustagent.user.mapper.SysPermissionMapper;
import io.trustagent.user.mapper.SysRoleMapper;
import io.trustagent.user.mapper.SysUserMapper;
import io.trustagent.user.mapper.SysUserRoleMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 账号、角色、权限的读取与维护。 */
@Service
@RequiredArgsConstructor
public class UserAccountService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;

    public SysUser findByUsername(String username) {
        return userMapper.selectByUsername(username);
    }

    public SysUser findById(Long id) {
        return userMapper.selectById(id);
    }

    public List<String> roleCodes(Long userId) {
        return roleMapper.selectByUserId(userId).stream().map(SysRole::getCode).toList();
    }

    public List<String> permissionCodes(Long userId) {
        return permissionMapper.selectByUserId(userId).stream().map(SysPermission::getCode).toList();
    }

    public UserPrincipal buildPrincipal(SysUser user) {
        return new UserPrincipal(user.getId(), user.getUsername(), user.getDisplayName(), user.getStatus(),
                roleCodes(user.getId()), permissionCodes(user.getId()));
    }

    public List<SysUser> listAll() {
        return userMapper.selectAllOrdered();
    }

    @Transactional
    public SysUser createUser(String username, String rawPassword, String displayName, String email,
                              List<String> roleCodes) {
        if (userMapper.selectByUsername(username) != null) {
            throw new BizException(ErrorCode.DUPLICATE_RESOURCE, "用户名已存在: " + username);
        }
        if (rawPassword == null || rawPassword.length() < 8) {
            throw BizException.badRequest("密码长度至少 8 位");
        }
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setDisplayName(displayName == null ? username : displayName);
        user.setEmail(email);
        user.setStatus(UserStatus.ACTIVE);
        userMapper.insert(user);
        replaceRoles(user.getId(), roleCodes);
        return user;
    }

    @Transactional
    public void replaceRoles(Long userId, List<String> roleCodes) {
        List<String> codes = roleCodes == null ? List.of() : roleCodes;
        if (codes.isEmpty()) {
            throw BizException.badRequest("至少需要指定一个角色");
        }
        List<SysRole> roles = codes.stream().map(code -> {
            SysRole role = roleMapper.selectByCode(code);
            if (role == null) {
                throw BizException.badRequest("角色不存在: " + code);
            }
            return role;
        }).toList();

        userRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, userId));
        for (SysRole role : roles) {
            SysUserRole link = new SysUserRole();
            link.setUserId(userId);
            link.setRoleId(role.getId());
            userRoleMapper.insert(link);
        }
    }

    public void changePassword(Long userId, String rawPassword) {
        if (rawPassword == null || rawPassword.length() < 8) {
            throw BizException.badRequest("密码长度至少 8 位");
        }
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        userMapper.updateById(user);
    }

    public void setStatus(Long userId, String status) {
        if (!UserStatus.ACTIVE.equals(status) && !UserStatus.DISABLED.equals(status)) {
            throw BizException.badRequest("非法的状态值: " + status);
        }
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        user.setStatus(status);
        userMapper.updateById(user);
    }

    public boolean verifyPassword(SysUser user, String rawPassword) {
        return rawPassword != null && passwordEncoder.matches(rawPassword, user.getPasswordHash());
    }

    public void touchLastLogin(Long userId) {
        userMapper.touchLastLogin(userId);
    }

    /** 供 DTO 组装使用。 */
    public record UserView(Long id, String username, String displayName, String email, String status,
                           List<String> roles, java.time.LocalDateTime createdAt) {
    }

    public UserView toView(SysUser user) {
        return new UserView(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(),
                user.getStatus(), roleCodes(user.getId()), user.getCreatedAt());
    }
}
