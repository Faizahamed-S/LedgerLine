package com.ledgerline.admin;

import com.ledgerline.account.AccountResponse;
import com.ledgerline.domain.AccountStatus;
import com.ledgerline.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/accounts")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAccountController {

    private final AdminAccountService adminAccountService;

    public AdminAccountController(AdminAccountService adminAccountService) {
        this.adminAccountService = adminAccountService;
    }

    @GetMapping
    @Operation(summary = "List customer accounts by status",
            description = "ADMIN only. Returns CUSTOMER accounts with the given status (default PENDING), "
                    + "e.g. the approval queue.")
    public List<AccountResponse> listCustomerAccounts(
            @RequestParam(defaultValue = "PENDING") AccountStatus status) {
        return adminAccountService.listCustomerAccounts(status);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Approve, freeze or unfreeze an account",
            description = "ADMIN only. Allowed: PENDING->ACTIVE, ACTIVE->FROZEN, FROZEN->ACTIVE; anything else "
                    + "is 400. The EXTERNAL account is 403. Moves no money, but a FROZEN account refuses all money "
                    + "movements. Written to the audit log.")
    public AccountResponse updateStatus(@AuthenticationPrincipal CurrentUser admin,
                                        @PathVariable Long id,
                                        @Valid @RequestBody UpdateAccountStatusRequest request) {
        return adminAccountService.updateStatus(admin, id, request);
    }
}
