package com.ledgerline.account;

import org.springframework.http.HttpStatus;

/** Why a money request was refused. {@code reason} is stored as transactions.reject_reason. */
public record Refusal(String reason, HttpStatus status) {

    public static final String NOT_AUTHORISED = "NOT_AUTHORISED";
    public static final String ACCOUNT_NOT_ACTIVE = "ACCOUNT_NOT_ACTIVE";
    public static final String DESTINATION_UNAVAILABLE = "DESTINATION_UNAVAILABLE";
    public static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";

    public static final String DESTINATION_UNAVAILABLE_MESSAGE = "Destination account not available";

    /** 404 when the user may not even see the account, 403 when they may see it but not act on it. */
    public static Refusal notAuthorised(HttpStatus status) {
        return new Refusal(NOT_AUTHORISED, status);
    }

    public static Refusal accountNotActive() {
        return new Refusal(ACCOUNT_NOT_ACTIVE, HttpStatus.CONFLICT);
    }

    public static Refusal destinationUnavailable() {
        return new Refusal(DESTINATION_UNAVAILABLE, HttpStatus.NOT_FOUND);
    }

    public static Refusal insufficientFunds() {
        return new Refusal(INSUFFICIENT_FUNDS, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    public String message() {
        return switch (reason) {
            case NOT_AUTHORISED -> status == HttpStatus.NOT_FOUND
                    ? AccountAccessPolicy.NOT_FOUND
                    : AccountAccessPolicy.FORBIDDEN;
            case ACCOUNT_NOT_ACTIVE -> AccountAccessPolicy.NOT_ACTIVE;
            case DESTINATION_UNAVAILABLE -> DESTINATION_UNAVAILABLE_MESSAGE;
            case INSUFFICIENT_FUNDS -> "Insufficient funds";
            default -> reason;
        };
    }
}
