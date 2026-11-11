package com.ledgerline.auth;

import com.ledgerline.common.Emails;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 254) String email,
        @NotBlank @Size(max = 72) String password) {

    public LoginRequest {
        email = Emails.normalize(email);
    }
}
