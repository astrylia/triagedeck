package com.triagedeck.auth.security;

import com.triagedeck.auth.token.JwtProperties;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
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
                .authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/api/auth/**",
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
    PasswordEncoder passwordEncoder() {
        // 新密码用 Argon2id（OWASP 首选），存进数据库的值带 {argon2} 前缀。
        // 校验时按前缀选算法，所以以前存的 {bcrypt} 密码依然能登录，不需要迁移数据。
        Map<String, PasswordEncoder> encoders = Map.of(
                "argon2",
                Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(),
                "bcrypt",
                new BCryptPasswordEncoder());
        return new DelegatingPasswordEncoder("argon2", encoders);
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
