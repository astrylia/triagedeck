package com.triagedeck.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        // Argon2 没有长度上限，但仍要设上限：否则别人提交超长密码，会让服务器花大量时间和内存算哈希
        @NotBlank @Size(min = 8, max = 128) String password,
        @NotBlank @Size(max = 100) String name) {}
