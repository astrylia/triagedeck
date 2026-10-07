package com.triagedeck.common;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 统一把异常转成 RFC 9457 格式的错误响应（ProblemDetail），并额外带一个 code 字段（见 ErrorCode）。
 * 继承 ResponseEntityExceptionHandler 后，Spring 自带的异常（参数校验失败、JSON 格式错误等）也返回同样的格式。
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 所有业务异常都走这一个方法，新增业务异常不需要再改这里。 */
    @ExceptionHandler(BusinessException.class)
    ProblemDetail handleBusiness(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), e.getMessage());
        problem.setProperty("code", code.name());
        return problem;
    }

    /** @Valid 校验失败：补上 code，并列出每个出错的字段，方便前端标红对应输入框。 */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ex.getBody();
        problem.setDetail(ErrorCode.VALIDATION_FAILED.message());
        problem.setProperty("code", ErrorCode.VALIDATION_FAILED.name());
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /**
     * 兜底：其他方法都没处理的异常，统一返回 500 INTERNAL_ERROR。
     * 完整堆栈只写进日志，响应里不带异常信息，避免把 SQL、类名等内部细节暴露给客户端。
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception e) throws Exception {
        // 权限相关的异常要交还给 Spring Security 处理（返回 401/403），不能被这里吞成 500
        if (e instanceof AccessDeniedException || e instanceof AuthenticationException) {
            throw e;
        }
        log.error("Unhandled exception", e);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), code.message());
        problem.setProperty("code", code.name());
        return problem;
    }
}
