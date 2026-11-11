package com.ledgerline.auth;

import com.ledgerline.common.Emails;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 200) String fullName,
        @NotNull @Size(min = 8, max = 72, message = "must be between 8 and 72 characters") String password) {

    public RegisterRequest {
        email = Emails.normalize(email);
        fullName = fullName == null ? null : fullName.trim();
    }
}
