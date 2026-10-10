package com.triagedeck.auth.link;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendLoginLinkRequest(
        @NotBlank @Email @Size(max = 254) String email) {}
