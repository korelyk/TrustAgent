package io.trustagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 全部配置项集中在这里，禁止在代码里散落硬编码常量。 */
@Data
@ConfigurationProperties(prefix = "trustagent")
public class TrustAgentProperties {

    private Jwt jwt = new Jwt();
    private Internal internal = new Internal();
    private AiService aiService = new AiService();
    private RateLimit rateLimit = new RateLimit();
    private Eval eval = new Eval();
    private Audit audit = new Audit();
    private Bootstrap bootstrap = new Bootstrap();
    private Masking masking = new Masking();
    private Runtime runtime = new Runtime();
    private Security security = new Security();

    @Data
    public static class Jwt {
        /** HMAC-SHA256 密钥。留空时启动阶段生成进程级随机密钥并告警（仅适用于本机开发）。 */
        private String secret = "";
        private String issuer = "trustagent";
        private long ttlSeconds = 7200;
    }

    @Data
    public static class Internal {
        /**
         * Java ↔ Python 的内部凭证。与用户 JWT 完全隔离：
         * 不同来源、不同过滤器、不同密钥。
         */
        private String token = "";
        private String header = "X-Internal-Token";
    }

    @Data
    public static class AiService {
        private String baseUrl = "http://localhost:8000";
        private int connectTimeoutMs = 2000;
        private int readTimeoutMs = 20000;
    }

    @Data
    public static class RateLimit {
        private boolean enabled = true;
        private Window chat = new Window(30, 60);
        private Window login = new Window(10, 60);
        private Window evalRun = new Window(10, 60);
    }

    @Data
    public static class Window {
        private int limit;
        private int windowSeconds;

        public Window() {
        }

        public Window(int limit, int windowSeconds) {
            this.limit = limit;
            this.windowSeconds = windowSeconds;
        }
    }

    @Data
    public static class Eval {
        private String streamKey = "eval.jobs";
        private String deadLetterKey = "eval.jobs.dlq";
        private String consumerGroup = "cg:eval";
        private int maxDeliveries = 3;
        private int jobTimeoutSeconds = 600;
        private int maxCasesPerDataset = 2000;
    }

    @Data
    public static class Audit {
        private boolean enabled = true;
        private int detailMaxLength = 2000;
    }

    @Data
    public static class Bootstrap {
        /** 演示管理员账号，仅在两者都非空且库中无该用户时创建。生产请留空。 */
        private String adminUsername = "";
        private String adminPassword = "";
    }

    @Data
    public static class Masking {
        private int previewMaxLength = 1000;
    }

    @Data
    public static class Runtime {
        /** redis | memory。memory 仅供 dev/test，表示限流与评测队列使用进程内实现。 */
        private String infrastructure = "redis";
    }

    @Data
    public static class Security {
        /** 仅 dev：允许访问 H2 控制台（生产必须为 false）。 */
        private boolean exposeH2Console = false;

        /**
         * 是否信任反向代理写入的 {@code X-Forwarded-For}。**默认关闭**。
         *
         * <p>关闭时取 {@code request.getRemoteAddr()}（TCP 层的对端地址，客户端无法伪造）；
         * 开启时取 XFF 的**最后一段**，理由见 {@code AuthController#clientIp} 的注释。
         *
         * <p>为什么默认关闭：XFF 是**请求头**，任何人都能写。如果无脑信任它，
         * 攻击者只要每次换一个假的 XFF 就能把登录限流拆成互不相干的桶——
         * 实测对同一账号连打 30 次登录失败，一次 429 都不会出现，限流等于不存在。
         * 只有"确定所有外部流量都必须经过自己可控的反向代理"时才应该打开。
         */
        private boolean trustForwardedFor = false;
    }
}
