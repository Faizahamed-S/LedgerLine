package com.ledgerline.anomaly;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewFlagRequest(@NotNull FlagDecision decision, @NotBlank @Size(max = 500) String note) {

    public ReviewFlagRequest {
        note = note == null ? null : note.trim();
    }
}
