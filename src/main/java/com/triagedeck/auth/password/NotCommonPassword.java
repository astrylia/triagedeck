package com.triagedeck.auth.password;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 拒绝常见、容易被猜到的密码（规则见 PasswordBlocklist）。
 * 标在整个请求类上而不是 password 字段上，因为判断时还要用到同一个请求里的邮箱和名字；
 * 被标注的类要实现 PasswordCandidate。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NotCommonPasswordValidator.class)
public @interface NotCommonPassword {

    // NIST 要求告诉用户密码为什么被拒绝
    String message() default "is too common or too easy to guess (for example your email or name); choose another one";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
