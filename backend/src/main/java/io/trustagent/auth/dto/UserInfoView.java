package io.trustagent.auth.dto;

import java.util.List;

/** 当前登录用户信息。前端只用它做菜单显示，真正的拦截在服务端。 */
public record UserInfoView(Long id, String username, String displayName, List<String> roles,
                           List<String> permissions) {
}
