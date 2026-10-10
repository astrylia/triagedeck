package com.triagedeck.auth.security;

import com.triagedeck.auth.token.JwtProperties;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, ProblemDetailAuthenticationEntryPoint authenticationEntryPoint) throws Exception {
        http
                // 无状态 REST API：不用 Session，也就不需要 CSRF 防护
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // /error 是 Spring Boot 内置的错误页，出错时请求会被转发到这里生成 500 响应；
                // 不放行的话，未登录接口出错时会被拦成一个空的 401，真正的错误就被掩盖了
                // /v3/api-docs 和 /swagger-ui 是接口文档，不需要登录就能看
                // /api/invitations/accept 是凭邀请链接加入组织，链接本身就证明了邮箱归属，打开它的人可能还没有账号
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/api/auth/**",
                                "/api/invitations/accept",
                                "/actuator/health",
                                "/error",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                // 其余请求都要带 Authorization: Bearer <token>，由 Spring Security 校验签名和过期时间
                .oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(Customizer.withDefaults()).authenticationEntryPoint(authenticationEntryPoint))
                // 没带 token 的请求走这里；带了但无效的走上面 oauth2ResourceServer 里的那个。两处都返回同样格式的 401
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(authenticationEntryPoint));
        return http.build();
    }

    @Bean
    SecretKey jwtSigningKey(JwtProperties properties) {
        return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    // 编码器和解码器都写死 HS256，签发和校验用的算法一定一致。
    // withSecretKey 会把 HS256 设成默认 header，TokenService 签发时不用再自己带 header。
    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return NimbusJwtEncoder.withSecretKey(jwtSigningKey)
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }
}
