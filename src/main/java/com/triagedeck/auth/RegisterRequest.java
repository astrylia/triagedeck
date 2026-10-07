package com.triagedeck.auth;

import com.triagedeck.common.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        // bcrypt 最多只接受 72 字节；按字节而不是字符限制，一个汉字占 3 字节
        @NotBlank @Size(min = 8) @MaxUtf8Bytes(72) String password,
        @NotBlank @Size(max = 100) String name) {}
