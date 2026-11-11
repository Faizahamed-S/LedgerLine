package com.ledgerline.money;

import com.ledgerline.common.BadRequestException;
import com.ledgerline.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class MoneyController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final String IDEMPOTENCY_KEY_REGEX = "^[A-Za-z0-9_-]{8,100}$";
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile(IDEMPOTENCY_KEY_REGEX);
    private static final String IDEMPOTENCY_KEY_DESCRIPTION = "Unique per money request, chosen by the client. "
            + "Repeating a request with the same key returns the original result (header Idempotent-Replayed: true) "
            + "and moves no money. Reusing it for a different request returns 409.";
    private static final String REFUSAL_NOTE = " A refused request is still recorded as a REJECTED transaction and "
            + "returned with status 404/403 (not allowed), 409 (account not ACTIVE) or 422 (insufficient funds).";

    private final MoneyService moneyService;
    private final StatementService statementService;

    public MoneyController(MoneyService moneyService, StatementService statementService) {
        this.moneyService = moneyService;
        this.statementService = statementService;
    }

    @PostMapping("/accounts/{id}/deposits")
    @Operation(summary = "Deposit money into an account",
            description = "HOLDER: into their own ACTIVE account. ADMIN: into any ACTIVE customer account. "
                    + "AUDITOR: never (403). Moves amountPaise from the EXTERNAL account into the account: the "
                    + "account is credited and EXTERNAL is debited, as two ledger entries." + REFUSAL_NOTE)
    public ResponseEntity<TransactionResponse> deposit(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable Long id,
            @Parameter(in = ParameterIn.HEADER, name = IDEMPOTENCY_KEY_HEADER, required = true,
                    description = IDEMPOTENCY_KEY_DESCRIPTION, schema = @Schema(pattern = IDEMPOTENCY_KEY_REGEX))
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody AmountRequest request) {
        String key = requireValidKey(idempotencyKey);
        return toResponse(moneyService.deposit(user, key, id, request.amountPaise()));
    }

    @PostMapping("/accounts/{id}/withdrawals")
    @Operation(summary = "Withdraw money from an account",
            description = "HOLDER only, from their own ACTIVE account. ADMIN and AUDITOR: never (403). Moves "
                    + "amountPaise out of the account to the EXTERNAL account: the account is debited and EXTERNAL "
                    + "is credited. Refused if the balance is lower than the amount." + REFUSAL_NOTE)
    public ResponseEntity<TransactionResponse> withdraw(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable Long id,
            @Parameter(in = ParameterIn.HEADER, name = IDEMPOTENCY_KEY_HEADER, required = true,
                    description = IDEMPOTENCY_KEY_DESCRIPTION, schema = @Schema(pattern = IDEMPOTENCY_KEY_REGEX))
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody AmountRequest request) {
        String key = requireValidKey(idempotencyKey);
        return toResponse(moneyService.withdraw(user, key, id, request.amountPaise()));
    }

    @PostMapping("/transfers")
    @Operation(summary = "Transfer money between accounts",
            description = "HOLDER only, from their own ACTIVE account into any ACTIVE customer account. ADMIN and "
                    + "AUDITOR: never (403). Debits the source and credits the destination by amountPaise. A "
                    + "destination that is missing, EXTERNAL, PENDING or FROZEN always returns the same 404 "
                    + "\"Destination account not available\". Same source and destination is 400." + REFUSAL_NOTE)
    public ResponseEntity<TransactionResponse> transfer(
            @AuthenticationPrincipal CurrentUser user,
            @Parameter(in = ParameterIn.HEADER, name = IDEMPOTENCY_KEY_HEADER, required = true,
                    description = IDEMPOTENCY_KEY_DESCRIPTION, schema = @Schema(pattern = IDEMPOTENCY_KEY_REGEX))
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        String key = requireValidKey(idempotencyKey);
        return toResponse(moneyService.transfer(
                user, key, request.fromAccountId(), request.toAccountId(), request.amountPaise()));
    }

    @GetMapping("/accounts/{id}/statement")
    @Operation(summary = "Get an account statement",
            description = "HOLDER: own accounts only (404 for anything else). ADMIN and AUDITOR: any account. "
                    + "Read-only, moves no money. Returns the current balance and up to 500 lines, newest first: "
                    + "posted debits/credits and refused requests. Holders see \"another user\" instead of the "
                    + "email of anyone else who initiated a line.")
    public StatementResponse statement(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id) {
        return statementService.statement(user, id);
    }

    private static String requireValidKey(String idempotencyKey) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new BadRequestException(
                    IDEMPOTENCY_KEY_HEADER + " header is required and must match " + IDEMPOTENCY_KEY_REGEX);
        }
        return idempotencyKey;
    }

    private static ResponseEntity<TransactionResponse> toResponse(MoneyResult result) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(result.status());
        if (result.replayed()) {
            response.header(REPLAYED_HEADER, "true");
        }
        return response.body(result.body());
    }
}
