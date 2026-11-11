package com.ledgerline.account;

import com.ledgerline.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('HOLDER')")
    @Operation(summary = "Request a new account",
            description = "HOLDER only. Creates a CUSTOMER account owned by the caller, status PENDING, balance 0. "
                    + "An ADMIN must approve it before money can move. At most 5 accounts per holder (409).")
    public AccountResponse requestAccount(@AuthenticationPrincipal CurrentUser user,
                                          @Valid @RequestBody CreateAccountRequest request) {
        return accountService.requestAccount(user, request);
    }

    @GetMapping
    @Operation(summary = "List accounts",
            description = "Any logged-in user. HOLDER sees only their own accounts; ADMIN and AUDITOR see all "
                    + "accounts, including the EXTERNAL account.")
    public List<AccountResponse> listAccounts(@AuthenticationPrincipal CurrentUser user) {
        return accountService.listAccounts(user);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one account",
            description = "HOLDER: own accounts only (404 for anything else). ADMIN and AUDITOR: any account.")
    public AccountResponse getAccount(@AuthenticationPrincipal CurrentUser user, @PathVariable Long id) {
        return accountService.getAccount(user, id);
    }
}
