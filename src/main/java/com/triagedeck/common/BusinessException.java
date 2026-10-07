package com.triagedeck.common;

/**
 * 业务异常。用法：throw new BusinessException(ErrorCode.EMAIL_ALREADY_USED)。
 * 抛哪种错误由 ErrorCode 决定，GlobalExceptionHandler 统一转成响应。
 * 只有某种错误需要被单独 catch，或要携带额外数据时，才为它单独写子类。
 * 异常的 message 就是返回给客户端的 detail，所以不要往里面放敏感信息。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    public BusinessException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
