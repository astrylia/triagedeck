package com.triagedeck.auth.verification;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendRegistrationLinkRequest(
        @NotBlank @Email @Size(max = 254) String email) {}
