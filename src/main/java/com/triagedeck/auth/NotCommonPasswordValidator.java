package com.triagedeck.auth;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Spring 创建校验器时会注入 PasswordBlocklist，所以构造方法可以直接要这个 bean。 */
public class NotCommonPasswordValidator implements ConstraintValidator<NotCommonPassword, RegisterRequest> {

    private final PasswordBlocklist blocklist;

    public NotCommonPasswordValidator(PasswordBlocklist blocklist) {
        this.blocklist = blocklist;
    }

    @Override
    public boolean isValid(RegisterRequest request, ConstraintValidatorContext context) {
        // 密码为空交给 @NotBlank 判断
        if (request.password() == null) {
            return true;
        }
        String email = request.email();
        int at = email == null ? -1 : email.indexOf('@');
        String emailLocalPart = at > 0 ? email.substring(0, at) : null;
        if (!blocklist.isBlocked(request.password(), email, emailLocalPart, request.name())) {
            return true;
        }
        // 默认错误会挂在整个对象上；改成挂在 password 字段上，响应的 errors 里才会出现 "field": "password"
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("password")
                .addConstraintViolation();
        return false;
    }
}
