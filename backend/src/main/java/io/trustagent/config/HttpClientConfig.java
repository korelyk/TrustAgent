package io.trustagent.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 调 Python 执行面用的 HTTP 客户端。
 *
 * <p>必须显式设置连接与读取超时：默认无超时会让一个卡住的执行面拖垮控制面的线程池，
 * 这是“网关型服务”最常见的雪崩来源之一。
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public ClientHttpRequestFactory javaNetHttpClientRequestFactory(TrustAgentProperties props) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(200, props.getAiService().getConnectTimeoutMs())))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(Math.max(500, props.getAiService().getReadTimeoutMs())));
        return factory;
    }

    @Bean
    public RestClient.Builder restClientBuilder(ClientHttpRequestFactory requestFactory) {
        return RestClient.builder().requestFactory(requestFactory);
    }
}
