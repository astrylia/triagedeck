package com.triagedeck.auth.security;

import com.triagedeck.common.ErrorCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 没带 token、token 无效或过期时的 401 响应。
 *
 * <p>Spring Security 默认只返回一个空的 401，和其他接口的错误格式（ProblemDetail + code）对不上。
 * 这里先交给默认实现设置状态码和 WWW-Authenticate 头，再补上和 GlobalExceptionHandler 一样格式的响应体。
 * 这个错误发生在请求进入 Controller 之前的过滤器里，所以 @RestControllerAdvice 管不到，只能在这里处理。
 */
@Component
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final AuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
    private final JsonMapper jsonMapper;

    public ProblemDetailAuthenticationEntryPoint(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException, ServletException {
        bearer.commence(request, response, authException);
        ErrorCode code = ErrorCode.UNAUTHENTICATED;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", code.status().getReasonPhrase());
        body.put("status", code.status().value());
        body.put("detail", code.message());
        body.put("instance", request.getRequestURI());
        body.put("code", code.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), body);
    }
}
