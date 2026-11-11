package com.ledgerline.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(@NotBlank @Size(max = 60) String name) {

    public CreateAccountRequest {
        name = name == null ? null : name.trim();
    }
}
