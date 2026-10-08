package com.triagedeck.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档的基本信息。启动后打开 http://localhost:8080/swagger-ui.html 查看和调试接口；
 * 前端用 http://localhost:8080/v3/api-docs 生成调用代码。
 *
 * <p>声明了 Bearer 认证，Swagger UI 右上角会出现 Authorize 按钮，填上登录拿到的 access token 就能调需要登录的接口。
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    OpenAPI triageDeckOpenApi() {
        return new OpenAPI()
                .info(new Info().title("TriageDeck API").version("v1").description("多租户客户支持工单系统"))
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
