package com.triagedeck.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        // bcrypt 只使用前 72 个字节，超过的部分会被忽略，所以这里限制上限
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 100) String name) {}
