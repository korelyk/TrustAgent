package io.trustagent;

import io.trustagent.config.TrustAgentProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * TrustAgent Java 控制面入口。
 *
 * <p>控制面负责：身份与权限、Agent 应用与安全策略、限流与幂等、Trace/安全事件/审计留痕、
 * 评测任务编排与指标复算。真正调用 LLM 与工具的执行面是 Python 服务（ai-service）。
 */
@SpringBootApplication
@MapperScan("io.trustagent.**.mapper")
@EnableConfigurationProperties(TrustAgentProperties.class)
public class TrustAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrustAgentApplication.class, args);
    }
}
