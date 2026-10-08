package com.triagedeck.auth.verification;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ResendVerificationEmailRequest(
        @NotBlank @Email String email) {}
