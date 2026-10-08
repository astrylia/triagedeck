package com.triagedeck.auth;

import com.triagedeck.auth.password.NotCommonPassword;
import com.triagedeck.auth.password.PasswordCandidate;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 常见密码检查要用到邮箱和名字，所以标在整个 record 上（见 NotCommonPassword）
@NotCommonPassword
public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        // 最少 15 位：NIST SP 800-63B（第 4 版）对"只靠密码登录、没有双因素认证"的要求。
        // 最多 128 位：Argon2 本身没有长度上限，但要防止超长密码让服务器花大量时间和内存算哈希。
        // 按 NIST 的要求，不强制"必须包含大小写、数字、符号"之类的组成规则。
        @NotBlank @Size(min = 15, max = 128) String password,
        @NotBlank @Size(max = 100) String name)
        implements PasswordCandidate {}
