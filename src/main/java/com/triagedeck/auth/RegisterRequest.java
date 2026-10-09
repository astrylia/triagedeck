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
        // 最多 128 位：给一个合理的上限，挡住明显异常的输入（NIST 要求上限至少 64 位）。
        // 这不是为了防止拖慢服务器：Argon2 只在第一步把密码压缩一次，之后的耗时和内存只由参数决定，跟密码长短基本无关。
        // 按 NIST 的要求，不强制"必须包含大小写、数字、符号"之类的组成规则。
        @NotBlank @Size(min = 15, max = 128) String password,
        @NotBlank @Size(max = 100) String name)
        implements PasswordCandidate {}
