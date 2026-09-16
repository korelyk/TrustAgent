package io.trustagent.bootstrap;

import io.trustagent.config.TrustAgentProperties;
import io.trustagent.user.SysUser;
import io.trustagent.user.UserAccountService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 首次启动时创建管理员账号。
 *
 * <p>为什么不在 Flyway 迁移里插用户：迁移脚本会进版本库，把默认口令写进去是常见的严重问题
 * （大量开源项目因此被扫出默认口令）。这里要求凭证由环境变量提供，且**只在库中没有该用户时**创建。
 *
 * <p>未配置时只打印一条提示，不创建任何账号，也不使用任何内置默认密码。
 */
@Component
public class BootstrapAdminRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    private final TrustAgentProperties props;
    private final UserAccountService userAccountService;

    public BootstrapAdminRunner(TrustAgentProperties props, UserAccountService userAccountService) {
        this.props = props;
        this.userAccountService = userAccountService;
    }

    @Override
    public void run(ApplicationArguments args) {
        String username = props.getBootstrap().getAdminUsername();
        String password = props.getBootstrap().getAdminPassword();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            log.info("未配置 TRUSTAGENT_BOOTSTRAP_ADMIN_USERNAME / PASSWORD，跳过管理员初始化。"
                    + "可用 scripts/seed-demo-data 创建演示账号。");
            return;
        }
        if (password.length() < 12) {
            log.warn("引导管理员密码长度小于 12 位，已跳过创建。请使用更强的密码。");
            return;
        }
        SysUser existing = userAccountService.findByUsername(username);
        if (existing != null) {
            log.info("管理员账号已存在，跳过创建: {}", username);
            return;
        }
        userAccountService.createUser(username, password, "系统管理员", null, List.of("ADMIN"));
        log.info("已创建管理员账号: {}", username);
    }
}
