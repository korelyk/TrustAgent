package io.trustagent.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI trustAgentOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("TrustAgent 控制面 API")
                        .version("0.1.0")
                        .description("""
                                AI Agent 安全网关与评测平台的控制面接口。
                                - 浏览器接口使用 JWT：先调 POST /api/auth/login，再点右上角 Authorize 填 `Bearer <token>`
                                - /internal/** 是服务间接口，需要 X-Internal-Token，不通过此处的 Authorize 传入
                                """))
                .components(new Components().addSecuritySchemes("bearer-jwt",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }
}
