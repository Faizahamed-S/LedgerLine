package com.ledgerline.common;

import org.springframework.http.HttpStatus;

import java.time.Instant;

public record ApiError(int status, String error, String message, Instant timestamp) {

    public static ApiError of(HttpStatus status, String message) {
        return new ApiError(status.value(), status.getReasonPhrase(), message, Instant.now());
    }
}
